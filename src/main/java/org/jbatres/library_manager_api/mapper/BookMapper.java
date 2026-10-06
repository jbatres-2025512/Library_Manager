package org.jbatres.library_manager_api.mapper;

import org.jbatres.library_manager_api.dto.request.BookRequest;
import org.jbatres.library_manager_api.dto.response.BookResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.springframework.stereotype.Component;

@Component
public class BookMapper {

    public BookResponse toResponse(Book book) {
        return new BookResponse(
                book.getId(),
                book.getIsbn(),
                book.getTitle(),
                book.getAuthor(),
                book.getCategory(),
                book.getTotalStock(),
                book.getAvailableStock());
    }

    /** New catalog entry: every copy starts available and the book is not deleted. */
    public Book toNewEntity(BookRequest request) {
        return Book.builder()
                .isbn(request.isbn())
                .title(request.title())
                .author(request.author())
                .category(request.category())
                .totalStock(request.totalStock())
                .availableStock(request.totalStock())
                .deleted(false)
                .build();
    }
}