package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.request.BookRequest;
import org.jbatres.library_manager_api.dto.response.BookResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.jbatres.library_manager_api.exception.DuplicateResourceException;
import org.jbatres.library_manager_api.exception.ResourceNotFoundException;
import org.jbatres.library_manager_api.mapper.BookMapper;
import org.jbatres.library_manager_api.repository.BookRepository;
import org.jbatres.library_manager_api.repository.BookSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog management. Stock-sensitive writes (update, delete) are single atomic SQL statements
 * (see BookRepository) so they stay correct while loans and returns modify availableStock
 * concurrently. Create is intentionally not @Transactional: one short save, and the UNIQUE
 * constraint on isbn is the final arbiter of concurrent duplicates.
 */
@Service
public class BookService {

    private static final Logger log = LoggerFactory.getLogger(BookService.class);
    private static final String DUPLICATE_ISBN =
            "A book with this ISBN already exists (it may belong to a deleted book)";

    private final BookRepository bookRepository;
    private final BookMapper bookMapper;

    public BookService(BookRepository bookRepository, BookMapper bookMapper) {
        this.bookRepository = bookRepository;
        this.bookMapper = bookMapper;
    }

    @Transactional(readOnly = true)
    public Page<BookResponse> search(String title, String category, Pageable pageable) {
        Pageable stable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), withIdTieBreaker(pageable.getSort()));
        return bookRepository
                .findAll(BookSpecifications.search(blankToNull(title), blankToNull(category)), stable)
                .map(bookMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public BookResponse getById(Long id) {
        return bookMapper.toResponse(findActiveOrThrow(id));
    }

    public BookResponse create(BookRequest request) {
        if (bookRepository.findByIsbn(request.isbn()).isPresent()) {
            throw new DuplicateResourceException(DUPLICATE_ISBN);
        }
        try {
            Book saved = bookRepository.saveAndFlush(bookMapper.toNewEntity(request));
            log.info("Book created: id={}", saved.getId());
            return bookMapper.toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException(DUPLICATE_ISBN);
        }
    }

    @Transactional
    public BookResponse update(Long id, BookRequest request) {
        int updated;
        try {
            updated = bookRepository.updateBook(id, request.isbn(), request.title(), request.author(),
                    request.category(), request.totalStock());
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException(DUPLICATE_ISBN);
        }

        if (updated == 0) {
            findActiveOrThrow(id); // 404 if missing or deleted
            throw new BusinessRuleException(
                    "Total stock cannot be lower than the number of copies currently on loan");
        }

        log.info("Book updated: id={}", id);
        return bookMapper.toResponse(findActiveOrThrow(id));
    }

    @Transactional
    public void delete(Long id) {
        if (bookRepository.softDeleteIfNoCopiesOnLoan(id) == 1) {
            log.info("Book soft-deleted: id={}", id);
            return;
        }
        findActiveOrThrow(id); // 404 if missing or already deleted
        throw new BusinessRuleException("Book cannot be deleted while copies are on loan");
    }

    private Book findActiveOrThrow(Long id) {
        return bookRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found with id " + id));
    }

    /** Pagination needs a total order; otherwise rows with equal sort values may repeat or vanish. */
    private Sort withIdTieBreaker(Sort sort) {
        if (sort.isUnsorted()) {
            return Sort.by("title", "id");
        }
        return sort.getOrderFor("id") != null ? sort : sort.and(Sort.by("id"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}