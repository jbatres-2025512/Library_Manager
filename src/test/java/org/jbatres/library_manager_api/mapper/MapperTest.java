package org.jbatres.library_manager_api.mapper;

import org.jbatres.library_manager_api.dto.request.BookRequest;
import org.jbatres.library_manager_api.dto.response.BookResponse;
import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MapperTest {

    private final LocalDate today = LocalDate.of(2026, 10, 6);
    private final BookMapper bookMapper = new BookMapper();
    private final LoanMapper loanMapper = new LoanMapper();
    private final UserMapper userMapper = new UserMapper();

    private User user() {
        return User.builder().id(7L).name("Reader").email("r@library.com").password("HASH")
                .role(Role.LECTOR).status(UserStatus.ACTIVO).build();
    }

    private Book book() {
        return Book.builder().id(3L).isbn("9780134685991").title("Effective Java").author("Bloch")
                .category("Programming").totalStock(5).availableStock(4).build();
    }

    private Loan loan(LocalDate expected, LoanStatus status) {
        return Loan.builder().id(1L).user(user()).book(book()).loanDate(expected.minusDays(14))
                .expectedReturnDate(expected).status(status).build();
    }

    @Test
    void newBook_startsFullyAvailableAndNotDeleted() {
        Book book = bookMapper.toNewEntity(new BookRequest("9780134685991", "T", "A", "C", 6));
        assertThat(book.getTotalStock()).isEqualTo(6);
        assertThat(book.getAvailableStock()).isEqualTo(6);
        assertThat(book.isDeleted()).isFalse();
    }

    @Test
    void bookResponse_copiesAllFields() {
        BookResponse response = bookMapper.toResponse(book());
        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.availableStock()).isEqualTo(4);
    }

    @Test
    void userResponse_doesNotExposePassword() {
        assertThat(userMapper.toResponse(user()).toString()).doesNotContain("HASH");
    }

    @Test
    void loanResponse_flagsPastDueOpenLoansAsOverdue() {
        LoanResponse pastDue = loanMapper.toResponse(loan(today.minusDays(1), LoanStatus.ACTIVO), today);
        assertThat(pastDue.overdue()).isTrue();
        assertThat(pastDue.userEmail()).isEqualTo("r@library.com");
        assertThat(pastDue.bookTitle()).isEqualTo("Effective Java");
    }

    @Test
    void loanResponse_notOverdueWhenDueTodayOrReturned() {
        assertThat(loanMapper.toResponse(loan(today, LoanStatus.ACTIVO), today).overdue()).isFalse();
        assertThat(loanMapper.toResponse(loan(today.minusDays(5), LoanStatus.DEVUELTO), today).overdue()).isFalse();
    }
}