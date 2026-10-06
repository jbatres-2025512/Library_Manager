package org.jbatres.library_manager_api.dto.response;

import org.jbatres.library_manager_api.entity.LoanStatus;

import java.time.LocalDate;

/**
 * Flat view of a loan (no nested entities). {@code overdue} is computed against today's date,
 * so a loan still stored as ACTIVO but past its due date is reported as overdue = true.
 */
public record LoanResponse(
        Long id,
        Long userId,
        String userName,
        String userEmail,
        Long bookId,
        String bookIsbn,
        String bookTitle,
        LocalDate loanDate,
        LocalDate expectedReturnDate,
        LocalDate actualReturnDate,
        LoanStatus status,
        boolean overdue) {
}