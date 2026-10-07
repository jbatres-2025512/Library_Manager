package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.jbatres.library_manager_api.exception.LoanAlreadyReturnedException;
import org.jbatres.library_manager_api.exception.ResourceNotFoundException;
import org.jbatres.library_manager_api.exception.UserSanctionedException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PATCH /prestamos/{id}/devolucion rules and concurrency (real DB, see AbstractLoanIntegrationTest). */
class LoanServiceReturnLoanTest extends AbstractLoanIntegrationTest {

    @Test
    void returnLoan_success_closesLoanAndRestoresStock() {
        User reader = newReader();
        Book book = newBook(5, 5);
        LoanResponse loan = borrow(reader, book);
        assertThat(availableStock(book)).isEqualTo(4);

        LoanResponse returned = loanService.returnLoan(loan.id());

        assertThat(returned.status()).isEqualTo(LoanStatus.DEVUELTO);
        assertThat(returned.actualReturnDate()).isEqualTo(LocalDate.now());
        assertThat(returned.overdue()).isFalse();
        assertThat(returned.userId()).isEqualTo(reader.getId());
        assertThat(returned.bookTitle()).isEqualTo(book.getTitle());
        assertThat(availableStock(book)).isEqualTo(5);
        assertThat(loanStatusInDb(loan.id())).isEqualTo("DEVUELTO");
    }

    @Test
    void returnLoan_twice_isRejectedAndStockIsRestoredOnlyOnce() {
        User reader = newReader();
        Book book = newBook(5, 5);
        LoanResponse loan = borrow(reader, book);
        loanService.returnLoan(loan.id());

        assertThatThrownBy(() -> loanService.returnLoan(loan.id()))
                .isInstanceOf(LoanAlreadyReturnedException.class)
                .isInstanceOf(BusinessRuleException.class);

        assertThat(availableStock(book)).isEqualTo(5);
    }

    @Test
    void returnLoan_unknownId_isNotFound() {
        assertThatThrownBy(() -> loanService.returnLoan(999_999_999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void returnLoan_frees_theReaderLimitSlot() {
        User reader = newReader();
        LoanResponse first = borrow(reader, newBook(5, 5));
        borrow(reader, newBook(5, 5));
        borrow(reader, newBook(5, 5));
        Book fourth = newBook(5, 5);
        assertThatThrownBy(() -> borrow(reader, fourth)).isInstanceOf(BusinessRuleException.class);

        loanService.returnLoan(first.id());

        assertThat(borrow(reader, fourth).status()).isEqualTo(LoanStatus.ACTIVO);
    }

    // ---------- sanction lifting ----------

    @Test
    void returnLoan_ofTheOnlyOverdueLoan_liftsTheSanction() {
        User reader = newReader();
        Book held = newBook(2, 1);
        Loan overdue = insertPastDueLoan(reader, held, 2);
        Book wanted = newBook(1, 1);
        assertThatThrownBy(() -> borrow(reader, wanted)).isInstanceOf(UserSanctionedException.class);
        assertThat(statusOf(reader)).isEqualTo(UserStatus.SANCIONADO);

        loanService.returnLoan(overdue.getId());

        assertThat(statusOf(reader)).isEqualTo(UserStatus.ACTIVO);
        assertThat(availableStock(held)).isEqualTo(2);
        assertThat(borrow(reader, wanted).status()).isEqualTo(LoanStatus.ACTIVO);
    }

    @Test
    void returnLoan_keepsTheSanctionWhileAnotherOverdueLoanRemains() {
        User reader = newReader();
        Loan first = insertPastDueLoan(reader, newBook(2, 1), 3);
        Loan second = insertPastDueLoan(reader, newBook(2, 1), 5);
        assertThatThrownBy(() -> borrow(reader, newBook(1, 1))).isInstanceOf(UserSanctionedException.class);

        loanService.returnLoan(first.getId());
        assertThat(statusOf(reader)).isEqualTo(UserStatus.SANCIONADO);

        loanService.returnLoan(second.getId());
        assertThat(statusOf(reader)).isEqualTo(UserStatus.ACTIVO);
    }

    // ---------- concurrency ----------

    @Test
    void concurrent_sameLoanReturnedByManyRequests_exactlyOneSucceedsAndStockIsRestoredOnce() throws Exception {
        User reader = newReader();
        Book book = newBook(5, 5);
        LoanResponse loan = borrow(reader, book);
        List<Callable<LoanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> loanService.returnLoan(loan.id()));
        }

        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof LoanResponse).count();
        long alreadyReturned = results.stream().filter(r -> r instanceof LoanAlreadyReturnedException).count();
        assertThat(successes).isEqualTo(1);
        assertThat(alreadyReturned).isEqualTo(9);
        assertThat(availableStock(book)).isEqualTo(5);   // restored exactly once
    }

    @Test
    void concurrent_returnsAndNewLoansOfTheSameReader_noDeadlocksAndStockStaysConsistent() throws Exception {
        User reader = newReader();
        List<Long> loanIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            loanIds.add(borrow(reader, newBook(5, 5)).id());
        }
        List<Callable<LoanResponse>> tasks = new ArrayList<>();
        for (Long loanId : loanIds) {
            tasks.add(() -> loanService.returnLoan(loanId));
        }
        for (int i = 0; i < 3; i++) {
            Book book = newBook(5, 5);
            tasks.add(() -> borrow(reader, book));
        }

        List<Object> results = runConcurrently(tasks);

        // Only business outcomes are acceptable: no deadlock / lock-timeout / inconsistency errors.
        assertThat(results).allMatch(r -> r instanceof LoanResponse || r instanceof BusinessRuleException);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM loans WHERE user_id = ? AND status <> 'DEVUELTO'",
                Integer.class, reader.getId())).isLessThanOrEqualTo(3);
        // Every book: available = total - open loans.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM books b
                WHERE b.isbn LIKE '97899%'
                  AND b.available_stock <> b.total_stock
                      - (SELECT count(*) FROM loans l WHERE l.book_id = b.id AND l.status <> 'DEVUELTO')
                """, Integer.class)).isZero();
    }
}