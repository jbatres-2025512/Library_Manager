package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.request.CreateLoanRequest;
import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.jbatres.library_manager_api.exception.ResourceNotFoundException;
import org.jbatres.library_manager_api.exception.UserSanctionedException;
import org.jbatres.library_manager_api.repository.BookRepository;
import org.jbatres.library_manager_api.repository.LoanRepository;
import org.jbatres.library_manager_api.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real transactions against the real database (NOT rolled back), so the concurrency tests are
 * meaningful. The test only touches its own rows (emails it-*@test.local, ISBNs 97899*) and
 * deletes them before and after every test; the seed data is never modified.
 * Needs the same environment variables as the app (DB_PASSWORD, JWT_SECRET).
 */
@SpringBootTest
class LoanServiceCreateLoanTest {

    private static final AtomicInteger ISBN_SEQ = new AtomicInteger(1);

    @Autowired
    private LoanService loanService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM loans WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'it-%@test.local')");
        jdbc.update("DELETE FROM loans WHERE book_id IN (SELECT id FROM books WHERE isbn LIKE '97899%')");
        jdbc.update("DELETE FROM users WHERE email LIKE 'it-%@test.local'");
        jdbc.update("DELETE FROM books WHERE isbn LIKE '97899%'");
    }

    // ---------- helpers ----------

    private User newUser(Role role, UserStatus status) {
        return userRepository.save(User.builder()
                .name("IT User")
                .email("it-" + UUID.randomUUID() + "@test.local")
                .password("not-a-real-hash")
                .role(role)
                .status(status)
                .build());
    }

    private User newReader() {
        return newUser(Role.LECTOR, UserStatus.ACTIVO);
    }

    private Book newBook(int total, int available) {
        String isbn = String.format("97899%08d", ISBN_SEQ.getAndIncrement());
        return bookRepository.save(Book.builder()
                .isbn(isbn).title("IT Book " + isbn).author("IT Author").category("Testing")
                .totalStock(total).availableStock(available)
                .build());
    }

    private int availableStock(Book book) {
        return bookRepository.findById(book.getId()).orElseThrow().getAvailableStock();
    }

    private int loanCount(User user) {
        return jdbc.queryForObject("SELECT count(*) FROM loans WHERE user_id = ?", Integer.class, user.getId());
    }

    private LoanResponse borrow(User user, Book book) {
        return loanService.createLoan(new CreateLoanRequest(user.getId(), book.getId()));
    }

    /** Fires all tasks at the same instant; returns each result (LoanResponse or the thrown exception). */
    private List<Object> runConcurrently(List<Callable<LoanResponse>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Callable<LoanResponse> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- happy path and basic rules ----------

    @Test
    void createLoan_success_setsDatesStatusAndDecrementsStock() {
        User reader = newReader();
        Book book = newBook(2, 2);

        LoanResponse response = borrow(reader, book);

        LocalDate today = LocalDate.now();
        assertThat(response.status()).isEqualTo(LoanStatus.ACTIVO);
        assertThat(response.loanDate()).isEqualTo(today);
        assertThat(response.expectedReturnDate()).isEqualTo(today.plusDays(14));
        assertThat(response.actualReturnDate()).isNull();
        assertThat(response.overdue()).isFalse();
        assertThat(response.userId()).isEqualTo(reader.getId());
        assertThat(response.bookId()).isEqualTo(book.getId());
        assertThat(availableStock(book)).isEqualTo(1);
        assertThat(loanCount(reader)).isEqualTo(1);
    }

    @Test
    void createLoan_noAvailableCopies_isRejectedAndCreatesNothing() {
        User reader = newReader();
        Book book = newBook(1, 0);

        assertThatThrownBy(() -> borrow(reader, book))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no available copies");

        assertThat(availableStock(book)).isZero();
        assertThat(loanCount(reader)).isZero();
    }

    @Test
    void createLoan_fourthActiveLoan_isRejectedAndStockIsUntouched() {
        User reader = newReader();
        borrow(reader, newBook(5, 5));
        borrow(reader, newBook(5, 5));
        borrow(reader, newBook(5, 5));
        Book fourth = newBook(5, 5);

        assertThatThrownBy(() -> borrow(reader, fourth))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("maximum");

        assertThat(availableStock(fourth)).isEqualTo(5);
        assertThat(loanCount(reader)).isEqualTo(3);
    }

    @Test
    void createLoan_forNonReaderUser_isRejected() {
        User librarian = newUser(Role.BIBLIOTECARIO, UserStatus.ACTIVO);
        Book book = newBook(1, 1);

        assertThatThrownBy(() -> borrow(librarian, book)).isInstanceOf(BusinessRuleException.class);
        assertThat(availableStock(book)).isEqualTo(1);
    }

    @Test
    void createLoan_unknownUserOrBook_isNotFound() {
        User reader = newReader();
        Book book = newBook(1, 1);

        assertThatThrownBy(() -> loanService.createLoan(new CreateLoanRequest(999_999_999L, book.getId())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> loanService.createLoan(new CreateLoanRequest(reader.getId(), 999_999_999L)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(availableStock(book)).isEqualTo(1);
    }

    @Test
    void createLoan_softDeletedBook_isNotFoundAndKeepsStock() {
        User reader = newReader();
        Book book = newBook(1, 1);
        book.setDeleted(true);
        bookRepository.save(book);

        assertThatThrownBy(() -> borrow(reader, book)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(availableStock(book)).isEqualTo(1);
    }

    // ---------- automatic sanction ----------

    @Test
    void createLoan_withOverdueLoan_sanctionsReaderAndKeepsTheSanctionCommitted() {
        User reader = newReader();
        Book heldBook = newBook(2, 1);
        LocalDate today = LocalDate.now();
        loanRepository.save(Loan.builder()
                .user(reader).book(heldBook)
                .loanDate(today.minusDays(30)).expectedReturnDate(today.minusDays(16))
                .status(LoanStatus.ACTIVO).build());
        Book wanted = newBook(1, 1);

        assertThatThrownBy(() -> borrow(reader, wanted)).isInstanceOf(UserSanctionedException.class);

        // The request was rejected, but noRollbackFor must have persisted the sanction:
        assertThat(userRepository.findById(reader.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.SANCIONADO);
        assertThat(jdbc.queryForObject("SELECT status FROM loans WHERE user_id = ?", String.class, reader.getId()))
                .isEqualTo("ATRASADO");
        assertThat(availableStock(wanted)).isEqualTo(1);

        // A sanctioned reader stays blocked.
        assertThatThrownBy(() -> borrow(reader, wanted)).isInstanceOf(UserSanctionedException.class);
        assertThat(availableStock(wanted)).isEqualTo(1);
        assertThat(loanCount(reader)).isEqualTo(1);
    }

    // ---------- concurrency ----------

    @Test
    void concurrent_manyReadersCompeteForTheLastCopy_exactlyOneWins() throws Exception {
        Book lastCopy = newBook(1, 1);
        List<Callable<LoanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            User reader = newReader();
            tasks.add(() -> borrow(reader, lastCopy));
        }

        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof LoanResponse).count();
        long rejected = results.stream().filter(r -> r instanceof BusinessRuleException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(rejected).isEqualTo(19);            // nothing else (no deadlocks / lock timeouts)
        assertThat(availableStock(lastCopy)).isZero(); // never negative
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loans WHERE book_id = ?", Integer.class,
                lastCopy.getId())).isEqualTo(1);
    }

    @Test
    void concurrent_sameReaderRequestsTenBooks_neverExceedsThreeLoans() throws Exception {
        User reader = newReader();
        List<Callable<LoanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Book book = newBook(5, 5);
            tasks.add(() -> borrow(reader, book));
        }

        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof LoanResponse).count();
        long rejected = results.stream().filter(r -> r instanceof BusinessRuleException).count();
        assertThat(successes).isEqualTo(3);
        assertThat(rejected).isEqualTo(7);
        assertThat(loanCount(reader)).isEqualTo(3);
        // 10 books x 5 copies, only 3 copies were taken:
        assertThat(jdbc.queryForObject("SELECT SUM(available_stock) FROM books WHERE isbn LIKE '97899%'",
                Long.class)).isEqualTo(47L);
    }
}