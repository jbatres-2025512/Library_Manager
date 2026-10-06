package org.jbatres.library_manager_api.mapper;

import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Loan;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Requires the loan's user and book to be already loaded (the repository queries use JOIN FETCH),
 * otherwise mapping would trigger lazy-loading queries (N+1).
 */
@Component
public class LoanMapper {

    public LoanResponse toResponse(Loan loan, LocalDate today) {
        return new LoanResponse(
                loan.getId(),
                loan.getUser().getId(),
                loan.getUser().getName(),
                loan.getUser().getEmail(),
                loan.getBook().getId(),
                loan.getBook().getIsbn(),
                loan.getBook().getTitle(),
                loan.getLoanDate(),
                loan.getExpectedReturnDate(),
                loan.getActualReturnDate(),
                loan.getStatus(),
                loan.isOverdue(today));
    }
}