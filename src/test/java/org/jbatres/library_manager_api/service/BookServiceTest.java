package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.request.BookRequest;
import org.jbatres.library_manager_api.dto.response.BookResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.exception.BusinessRuleException;
import org.jbatres.library_manager_api.exception.DuplicateResourceException;
import org.jbatres.library_manager_api.exception.ResourceNotFoundException;
import org.jbatres.library_manager_api.mapper.BookMapper;
import org.jbatres.library_manager_api.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit tests (Mockito): no Spring context and no database. */
@ExtendWith(MockitoExtension.class)
class BookServiceTest {

    private static final String ISBN = "9780134685991";

    @Mock
    private BookRepository bookRepository;

    private BookService bookService;

    @BeforeEach
    void setUp() {
        bookService = new BookService(bookRepository, new BookMapper());
    }

    private Book book(long id, int total, int available) {
        return Book.builder().id(id).isbn(ISBN).title("Effective Java").author("Bloch")
                .category("Programming").totalStock(total).availableStock(available).build();
    }

    private BookRequest request(int totalStock) {
        return new BookRequest(ISBN, "Effective Java", "Bloch", "Programming", totalStock);
    }

    // ---------- create ----------

    @Test
    void create_savesBookWithAllCopiesAvailable() {
        when(bookRepository.findByIsbn(ISBN)).thenReturn(Optional.empty());
        when(bookRepository.saveAndFlush(any(Book.class))).thenAnswer(invocation -> {
            Book saved = invocation.getArgument(0);
            saved.setId(11L);
            return saved;
        });

        BookResponse response = bookService.create(request(6));

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.totalStock()).isEqualTo(6);
        assertThat(response.availableStock()).isEqualTo(6);
    }

    @Test
    void create_existingIsbn_isRejectedBeforeSaving() {
        when(bookRepository.findByIsbn(ISBN)).thenReturn(Optional.of(book(1L, 5, 4)));

        assertThatThrownBy(() -> bookService.create(request(6)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(bookRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_concurrentDuplicate_isMappedToDuplicateResource() {
        when(bookRepository.findByIsbn(ISBN)).thenReturn(Optional.empty());
        when(bookRepository.saveAndFlush(any(Book.class)))
                .thenThrow(new DataIntegrityViolationException("uk_books_isbn"));

        assertThatThrownBy(() -> bookService.create(request(6)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ---------- update ----------

    @Test
    void update_returnsFreshBookAfterAtomicUpdate() {
        when(bookRepository.updateBook(3L, ISBN, "Effective Java", "Bloch", "Programming", 8)).thenReturn(1);
        when(bookRepository.findByIdAndDeletedFalse(3L)).thenReturn(Optional.of(book(3L, 8, 7)));

        BookResponse response = bookService.update(3L, request(8));

        assertThat(response.totalStock()).isEqualTo(8);
        assertThat(response.availableStock()).isEqualTo(7);
    }

    @Test
    void update_totalBelowCopiesOnLoan_isBusinessRuleViolation() {
        when(bookRepository.updateBook(3L, ISBN, "Effective Java", "Bloch", "Programming", 0)).thenReturn(0);
        when(bookRepository.findByIdAndDeletedFalse(3L)).thenReturn(Optional.of(book(3L, 5, 4)));

        assertThatThrownBy(() -> bookService.update(3L, request(0)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void update_missingOrDeletedBook_isNotFound() {
        when(bookRepository.updateBook(3L, ISBN, "Effective Java", "Bloch", "Programming", 8)).thenReturn(0);
        when(bookRepository.findByIdAndDeletedFalse(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.update(3L, request(8)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_duplicateIsbn_isMappedToDuplicateResource() {
        when(bookRepository.updateBook(3L, ISBN, "Effective Java", "Bloch", "Programming", 8))
                .thenThrow(new DataIntegrityViolationException("uk_books_isbn"));

        assertThatThrownBy(() -> bookService.update(3L, request(8)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ---------- delete ----------

    @Test
    void delete_bookWithoutLoans_isSoftDeleted() {
        when(bookRepository.softDeleteIfNoCopiesOnLoan(3L)).thenReturn(1);

        bookService.delete(3L);

        verify(bookRepository, never()).findByIdAndDeletedFalse(any());
    }

    @Test
    void delete_missingBook_isNotFound() {
        when(bookRepository.softDeleteIfNoCopiesOnLoan(3L)).thenReturn(0);
        when(bookRepository.findByIdAndDeletedFalse(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.delete(3L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_bookWithCopiesOnLoan_isBusinessRuleViolation() {
        when(bookRepository.softDeleteIfNoCopiesOnLoan(3L)).thenReturn(0);
        when(bookRepository.findByIdAndDeletedFalse(3L)).thenReturn(Optional.of(book(3L, 5, 4)));

        assertThatThrownBy(() -> bookService.delete(3L)).isInstanceOf(BusinessRuleException.class);
    }

    // ---------- read ----------

    @Test
    void getById_missingBook_isNotFound() {
        when(bookRepository.findByIdAndDeletedFalse(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.getById(9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void search_addsIdTieBreakerToTheRequestedSort() {
        Pageable requested = PageRequest.of(0, 5, Sort.by("category"));
        when(bookRepository.findAll(ArgumentMatchers.<Specification<Book>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(book(1L, 5, 4))));

        Page<BookResponse> page = bookService.search(" ", null, requested);

        ArgumentCaptor<Pageable> sent = ArgumentCaptor.forClass(Pageable.class);
        verify(bookRepository).findAll(ArgumentMatchers.<Specification<Book>>any(), sent.capture());
        assertThat(sent.getValue().getSort().stream().map(Sort.Order::getProperty))
                .containsExactly("category", "id");
        assertThat(page.getContent()).hasSize(1);
    }
}