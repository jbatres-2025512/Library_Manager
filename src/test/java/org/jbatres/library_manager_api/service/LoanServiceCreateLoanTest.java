package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.request.CreateLoanRequest;
import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.jbatres.library_manager_api.exception.ResourceNotFoundException;
import org.jbatres.library_manager_api.exception.UserSanctionedException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** POST /prestamos business rules and concurrency (real DB, see AbstractLoanIntegrationTest). */
class LoanServiceCreateLoanTest extends AbstractLoanIntegrationTest {

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
        var overdueLoan = insertPastDueLoan(reader, heldBook, 16);
        Book wanted = newBook(1, 1);

        assertThatThrownBy(() -> borrow(reader, wanted)).isInstanceOf(UserSanctionedException.class);

        // The request was rejected, but noRollbackFor must have persisted the sanction:
        assertThat(statusOf(reader)).isEqualTo(UserStatus.SANCIONADO);
        assertThat(loanStatusInDb(overdueLoan.getId())).isEqualTo("ATRASADO");
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