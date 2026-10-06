package org.jbatres.library_manager_api.repository;

import  org.jbatres.library_manager_api.entity.Book;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/** Dynamic, null-safe filters for GET /api/v1/libros. */
public final class BookSpecifications {

    private static final char ESCAPE = '\\';

    private BookSpecifications() {
    }

    /** Non-deleted books matching the optional title (contains) and category (equals) filters. */
    public static Specification<Book> search(String title, String category) {
        return notDeleted()
                .and(titleContains(title))
                .and(categoryEquals(category));
    }

    public static Specification<Book> notDeleted() {
        return (root, query, cb) -> cb.isFalse(root.get("deleted"));
    }

    public static Specification<Book> titleContains(String title) {
        if (title == null || title.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        String pattern = "%" + escapeLike(title.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.<String>get("title")), pattern, ESCAPE);
    }

    public static Specification<Book> categoryEquals(String category) {
        if (category == null || category.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.<String>get("category")), normalized);
    }

    /** Escapes LIKE wildcards so user input like "100%" or "a_b" is matched literally. */
    private static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}