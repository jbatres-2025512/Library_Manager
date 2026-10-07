package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.dto.response.PageResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GET /mis-prestamos and GET /atrasados logic (real DB, see AbstractLoanIntegrationTest). */
class LoanServiceQueryTest extends AbstractLoanIntegrationTest {

    private final LocalDate today = LocalDate.now();

    /** expectedReturnDate = loanDate + 14; DEVUELTO loans get a return date (DB CHECK requires it). */
    private Loan saveLoan(User user, Book book, LocalDate loanDate, LoanStatus status) {
        return loanRepository.save(Loan.builder()
                .user(user).book(book)
                .loanDate(loanDate)
                .expectedReturnDate(loanDate.plusDays(14))
                .actualReturnDate(status == LoanStatus.DEVUELTO ? loanDate.plusDays(3) : null)
                .status(status)
                .build());
    }

    // ---------- mis-prestamos ----------

    @Test
    void myLoans_returnsOnlyOwnLoans_newestFirstByDefault() {
        User a = newReader();
        User b = newReader();
        Loan oldest = saveLoan(a, newBook(2, 1), today.minusDays(10), LoanStatus.ACTIVO);
        Loan middle = saveLoan(a, newBook(2, 1), today.minusDays(5), LoanStatus.ACTIVO);
        Loan newest = saveLoan(a, newBook(2, 2), today.minusDays(1), LoanStatus.DEVUELTO);
        saveLoan(b, newBook(2, 1), today.minusDays(2), LoanStatus.ACTIVO);

        PageResponse<LoanResponse> page = loanService.getMyLoans(a.getId(), PageRequest.of(0, 10));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content()).extracting(LoanResponse::id)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(page.content()).allMatch(l -> l.userId().equals(a.getId()));
        assertThat(page.content().get(0).bookTitle()).isNotBlank();
    }

    @Test
    void myLoans_isPaginated() {
        User reader = newReader();
        for (int i = 1; i <= 5; i++) {
            saveLoan(reader, newBook(2, 1), today.minusDays(i), LoanStatus.ACTIVO);
        }

        PageResponse<LoanResponse> first = loanService.getMyLoans(reader.getId(), PageRequest.of(0, 2));
        PageResponse<LoanResponse> last = loanService.getMyLoans(reader.getId(), PageRequest.of(2, 2));

        assertThat(first.content()).hasSize(2);
        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.first()).isTrue();
        assertThat(last.content()).hasSize(1);
        assertThat(last.last()).isTrue();
    }

    @Test
    void myLoans_honoursWhitelistedSort_andRejectsOtherProperties() {
        User reader = newReader();
        Loan older = saveLoan(reader, newBook(2, 1), today.minusDays(9), LoanStatus.ACTIVO);
        Loan newer = saveLoan(reader, newBook(2, 1), today.minusDays(2), LoanStatus.ACTIVO);

        PageResponse<LoanResponse> ascending = loanService.getMyLoans(
                reader.getId(), PageRequest.of(0, 10, Sort.by("loanDate").ascending()));
        assertThat(ascending.content()).extracting(LoanResponse::id)
                .containsExactly(older.getId(), newer.getId());

        assertThatThrownBy(() -> loanService.getMyLoans(
                reader.getId(), PageRequest.of(0, 10, Sort.by("user.password"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Unsupported sort property");
        assertThatThrownBy(() -> loanService.getMyLoans(
                reader.getId(), PageRequest.of(0, 10, Sort.by("doesNotExist"))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void myLoans_forReaderWithoutLoans_isAnEmptyPage() {
        PageResponse<LoanResponse> page = loanService.getMyLoans(newReader().getId(), PageRequest.of(0, 10));
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // ---------- atrasados ----------

    @Test
    void overdueLoans_listsOnlyOpenPastDueLoans_mostOverdueFirst() {
        Loan overdueStillActivo = saveLoan(newReader(), newBook(2, 1), today.minusDays(16), LoanStatus.ACTIVO);   // due 2 days ago
        Loan overdueFlagged = saveLoan(newReader(), newBook(2, 1), today.minusDays(19), LoanStatus.ATRASADO);     // due 5 days ago
        Loan notDueYet = saveLoan(newReader(), newBook(2, 1), today.minusDays(3), LoanStatus.ACTIVO);
        Loan returnedLate = saveLoan(newReader(), newBook(2, 2), today.minusDays(30), LoanStatus.DEVUELTO);

        PageResponse<LoanResponse> page = loanService.getOverdueLoans(PageRequest.of(0, 100));

        List<Long> ids = page.content().stream().map(LoanResponse::id).toList();
        assertThat(ids).contains(overdueStillActivo.getId(), overdueFlagged.getId())
                .doesNotContain(notDueYet.getId(), returnedLate.getId());
        assertThat(ids.indexOf(overdueFlagged.getId())).isLessThan(ids.indexOf(overdueStillActivo.getId()));
        assertThat(page.content()).allMatch(LoanResponse::overdue);
        assertThat(page.content()).allMatch(l -> l.userEmail() != null && l.bookTitle() != null);
    }

    @Test
    void overdueLoans_dueTodayIsNotOverdueYet() {
        Loan dueToday = saveLoan(newReader(), newBook(2, 1), today.minusDays(14), LoanStatus.ACTIVO);

        PageResponse<LoanResponse> page = loanService.getOverdueLoans(PageRequest.of(0, 100));

        assertThat(page.content()).extracting(LoanResponse::id).doesNotContain(dueToday.getId());
    }

    // ---------- paging safety ----------

    @Test
    void pageSize_isCappedAtOneHundred() {
        PageResponse<LoanResponse> page = loanService.getOverdueLoans(PageRequest.of(0, 5000));
        assertThat(page.size()).isEqualTo(100);
    }
}