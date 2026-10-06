package org.jbatres.library_manager_api.repository;

import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises every repository query against the real PostgreSQL database (schema.sql + data.sql).
 * Each test runs in a transaction that is rolled back, so the data is left untouched.
 * Requires the same environment variables as the application (DB_PASSWORD, ...).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryQueriesTest {

    private static final String ISBN_BRAVE_NEW_WORLD = "9780060850524"; // stock 0
    private static final String ISBN_EFFECTIVE_JAVA = "9780134685991";  // 4 of 5 available
    private static final String ISBN_DDD = "9780321125217";             // 1 of 2 available
    private static final String ISBN_CLEAN_CODE = "9780132350884";      // 4 of 4 available

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private TestEntityManager entityManager;

    private Long userId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    private Book book(String isbn) {
        return bookRepository.findByIsbn(isbn).orElseThrow();
    }

    @Test
    void userQueries_findByEmailAndLockForUpdate() {
        assertThat(userRepository.existsByEmail("admin@library.com")).isTrue();
        assertThat(userRepository.existsByEmail("nobody@library.com")).isFalse();
        Long id = userId("reader1@library.com");
        assertThat(userRepository.findByIdForUpdate(id)).isPresent();
    }

    @Test
    void searchBooks_filtersByTitleAndCategoryCaseInsensitive() {
        Page<Book> page = bookRepository.findAll(
                BookSpecifications.search("DESIGN", "programming"),
                PageRequest.of(0, 10, Sort.by("title")));

        assertThat(page.getContent()).extracting(Book::getTitle)
                .containsExactly("Design Patterns", "Head First Design Patterns");
    }

    @Test
    void searchBooks_pagesWithoutFilters() {
        Page<Book> page = bookRepository.findAll(
                BookSpecifications.search(null, null),
                PageRequest.of(0, 3, Sort.by("title")));

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(10);
        assertThat(page.getTotalPages()).isEqualTo(4);
    }

    @Test
    void searchBooks_treatsLikeWildcardsLiterally() {
        Page<Book> page = bookRepository.findAll(
                BookSpecifications.search("%", null), PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void searchBooks_excludesSoftDeletedBooks() {
        Book book = book(ISBN_CLEAN_CODE);
        book.setDeleted(true);
        entityManager.flush();

        assertThat(bookRepository.findByIdAndDeletedFalse(book.getId())).isEmpty();
        Page<Book> page = bookRepository.findAll(
                BookSpecifications.search("clean code", null), PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void decrementAvailableStock_neverGoesBelowZero() {
        Long dddId = book(ISBN_DDD).getId();
        assertThat(bookRepository.decrementAvailableStock(dddId)).isEqualTo(1); // 1 -> 0
        assertThat(bookRepository.decrementAvailableStock(dddId)).isZero();     // rejected
        entityManager.clear();
        assertThat(book(ISBN_DDD).getAvailableStock()).isZero();

        Long outOfStockId = book(ISBN_BRAVE_NEW_WORLD).getId();
        assertThat(bookRepository.decrementAvailableStock(outOfStockId)).isZero();
    }

    @Test
    void incrementAvailableStock_neverExceedsTotal() {
        Long cleanCodeId = book(ISBN_CLEAN_CODE).getId(); // 4 of 4
        assertThat(bookRepository.incrementAvailableStock(cleanCodeId)).isZero();

        Long javaId = book(ISBN_EFFECTIVE_JAVA).getId();  // 4 of 5
        assertThat(bookRepository.incrementAvailableStock(javaId)).isEqualTo(1);
        assertThat(bookRepository.incrementAvailableStock(javaId)).isZero();
        entityManager.clear();
        assertThat(book(ISBN_EFFECTIVE_JAVA).getAvailableStock()).isEqualTo(5);
    }

    @Test
    void loanCounts_andOverdueDetection() {
        assertThat(loanRepository.countByUser_IdAndStatusIn(
                userId("reader2@library.com"), LoanStatus.OPEN_STATUSES)).isEqualTo(3);
        assertThat(loanRepository.countByUser_IdAndStatusIn(
                userId("reader1@library.com"), LoanStatus.OPEN_STATUSES)).isEqualTo(1);

        LocalDate today = LocalDate.now();
        assertThat(loanRepository.existsByUser_IdAndStatusInAndExpectedReturnDateBefore(
                userId("reader3@library.com"), LoanStatus.OPEN_STATUSES, today)).isTrue();
        assertThat(loanRepository.existsByUser_IdAndStatusInAndExpectedReturnDateBefore(
                userId("reader1@library.com"), LoanStatus.OPEN_STATUSES, today)).isFalse();

        assertThat(loanRepository.existsByBook_IdAndStatusIn(
                book(ISBN_EFFECTIVE_JAVA).getId(), LoanStatus.OPEN_STATUSES)).isTrue();
        assertThat(loanRepository.existsByBook_IdAndStatusIn(
                book(ISBN_CLEAN_CODE).getId(), LoanStatus.OPEN_STATUSES)).isFalse();
    }

    @Test
    void findOverdue_returnsPastDueOpenLoansWithUserAndBook() {
        Page<Loan> page = loanRepository.findOverdue(
                LoanStatus.OPEN_STATUSES, LocalDate.now(),
                PageRequest.of(0, 10, Sort.by("expectedReturnDate")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(l -> l.getUser().getEmail())
                .containsExactlyInAnyOrder("reader3@library.com", "reader4@library.com");
    }

    @Test
    void markOverdueByUserId_flagsOnlyPastDueActiveLoans() {
        Long reader3 = userId("reader3@library.com");
        assertThat(loanRepository.markOverdueByUserId(reader3, LocalDate.now())).isEqualTo(1);
        assertThat(loanRepository.markOverdueByUserId(reader3, LocalDate.now())).isZero();

        Long reader1 = userId("reader1@library.com");
        assertThat(loanRepository.markOverdueByUserId(reader1, LocalDate.now())).isZero();
    }

    @Test
    void markReturned_succeedsOnlyOnce() {
        Long reader1 = userId("reader1@library.com");
        Page<Loan> history = loanRepository.findByUserIdWithBook(
                reader1, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "loanDate")));
        assertThat(history.getTotalElements()).isEqualTo(2);

        Long activeLoanId = history.getContent().stream()
                .filter(l -> l.getStatus() == LoanStatus.ACTIVO)
                .findFirst().orElseThrow().getId();

        LocalDate today = LocalDate.now();
        assertThat(loanRepository.markReturned(activeLoanId, today)).isEqualTo(1);
        assertThat(loanRepository.markReturned(activeLoanId, today)).isZero(); // double return rejected

        Loan returned = loanRepository.findByIdWithDetails(activeLoanId).orElseThrow();
        assertThat(returned.getStatus()).isEqualTo(LoanStatus.DEVUELTO);
        assertThat(returned.getActualReturnDate()).isEqualTo(today);
    }
}