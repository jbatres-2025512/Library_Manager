package org.jbatres.library_manager_api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of POST /api/v1/prestamos. Dates and status are never accepted from the client:
 * the server sets loanDate = today, expectedReturnDate = loanDate + 14 days, status = ACTIVO.
 */
public record CreateLoanRequest(
        @NotNull(message = "User id is required")
        @Positive(message = "User id must be positive")
        Long userId,

        @NotNull(message = "Book id is required")
        @Positive(message = "Book id must be positive")
        Long bookId) {
}