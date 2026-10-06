package org.jbatres.library_manager_api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * Body of POST /api/v1/libros and PUT /api/v1/libros/{id}.
 * Only totalStock is sent by the client: availableStock is derived by the server
 * (equal to totalStock on creation, adjusted by the delta on update).
 * The ISBN is normalized (hyphens/spaces removed, upper-case) so "978-0-13-468599-1"
 * and "9780134685991" are the same book for the UNIQUE constraint.
 */
public record BookRequest(
        @NotBlank(message = "ISBN is required")
        @Pattern(regexp = "^(\\d{9}[\\dX]|\\d{13})$", message = "ISBN must be a valid ISBN-10 or ISBN-13")
        String isbn,

        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @NotBlank(message = "Author is required")
        @Size(max = 150, message = "Author must not exceed 150 characters")
        String author,

        @NotBlank(message = "Category is required")
        @Size(max = 100, message = "Category must not exceed 100 characters")
        String category,

        @NotNull(message = "Total stock is required")
        @Min(value = 0, message = "Total stock cannot be negative")
        @Max(value = 100000, message = "Total stock must not exceed 100000")
        Integer totalStock) {

    public BookRequest {
        isbn = isbn == null ? null : isbn.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        title = title == null ? null : title.trim();
        author = author == null ? null : author.trim();
        category = category == null ? null : category.trim();
    }
}