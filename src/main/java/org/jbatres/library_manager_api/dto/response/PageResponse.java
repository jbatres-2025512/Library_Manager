package org.jbatres.library_manager_api.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable JSON contract for paginated results. Spring's PageImpl is not meant to be serialized
 * directly (its JSON shape is not guaranteed), so controllers return this record instead.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}