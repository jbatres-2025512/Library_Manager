package org.jbatres.library_manager_api.dto.response;

public record BookResponse(
        Long id,
        String isbn,
        String title,
        String author,
        String category,
        int totalStock,
        int availableStock) {
}