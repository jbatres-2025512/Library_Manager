package org.jbatres.library_manager_api.dto;

import org.jbatres.library_manager_api.dto.request.BookRequest;
import org.jbatres.library_manager_api.dto.request.CreateLoanRequest;
import org.jbatres.library_manager_api.dto.request.LoginRequest;
import org.jbatres.library_manager_api.dto.request.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Bean Validation tests: no Spring context and no database required. */
class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(Object dto) {
        return validator.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    // ---------- RegisterRequest ----------

    @Test
    void register_validRequest_hasNoViolations() {
        assertThat(invalidFields(new RegisterRequest("Ana", "ana@library.com", "Secret123!"))).isEmpty();
    }

    @Test
    void register_normalizesNameAndEmail() {
        RegisterRequest request = new RegisterRequest("  Ana Lopez  ", "  Ana@Library.COM ", "Secret123!");
        assertThat(request.name()).isEqualTo("Ana Lopez");
        assertThat(request.email()).isEqualTo("ana@library.com");
    }

    @Test
    void register_invalidFields_areReported() {
        assertThat(invalidFields(new RegisterRequest("  ", "not-an-email", "short")))
                .containsExactlyInAnyOrder("name", "email", "password");
    }

    @Test
    void register_nullFields_areReported() {
        assertThat(invalidFields(new RegisterRequest(null, null, null)))
                .containsExactlyInAnyOrder("name", "email", "password");
    }

    @Test
    void register_passwordLongerThan72_isRejected() {
        assertThat(invalidFields(new RegisterRequest("Ana", "ana@library.com", "a".repeat(73))))
                .containsExactly("password");
    }

    @Test
    void register_toStringNeverPrintsPassword() {
        String text = new RegisterRequest("Ana", "ana@library.com", "Secret123!").toString();
        assertThat(text).doesNotContain("Secret123!").contains("***");
    }

    // ---------- LoginRequest ----------

    @Test
    void login_blankFields_areReported() {
        assertThat(invalidFields(new LoginRequest("", " "))).containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void login_toStringNeverPrintsPassword() {
        assertThat(new LoginRequest("a@b.com", "Secret123!").toString()).doesNotContain("Secret123!");
    }

    // ---------- BookRequest ----------

    @Test
    void book_validRequest_hasNoViolations() {
        assertThat(invalidFields(new BookRequest("9780134685991", "Effective Java", "Joshua Bloch", "Programming", 5)))
                .isEmpty();
    }

    @Test
    void book_isbnWithHyphensAndLowerCaseX_isNormalized() {
        assertThat(new BookRequest("978-0-13-468599-1", "T", "A", "C", 1).isbn()).isEqualTo("9780134685991");
        BookRequest isbn10 = new BookRequest("0-13-468599-x", "T", "A", "C", 1);
        assertThat(isbn10.isbn()).isEqualTo("013468599X");
        assertThat(invalidFields(isbn10)).isEmpty();
    }

    @Test
    void book_invalidIsbn_isRejected() {
        assertThat(invalidFields(new BookRequest("ABC123", "T", "A", "C", 1))).containsExactly("isbn");
    }

    @Test
    void book_invalidStock_isRejected() {
        assertThat(invalidFields(new BookRequest("9780134685991", "T", "A", "C", -1))).containsExactly("totalStock");
        assertThat(invalidFields(new BookRequest("9780134685991", "T", "A", "C", null))).containsExactly("totalStock");
        assertThat(invalidFields(new BookRequest("9780134685991", "T", "A", "C", 100001))).containsExactly("totalStock");
        assertThat(invalidFields(new BookRequest("9780134685991", "T", "A", "C", 0))).isEmpty();
    }

    @Test
    void book_titleTooLong_isRejected() {
        assertThat(invalidFields(new BookRequest("9780134685991", "x".repeat(256), "A", "C", 1)))
                .containsExactly("title");
    }

    // ---------- CreateLoanRequest ----------

    @Test
    void createLoan_validRequest_hasNoViolations() {
        assertThat(invalidFields(new CreateLoanRequest(1L, 2L))).isEmpty();
    }

    @Test
    void createLoan_nullOrNonPositiveIds_areRejected() {
        assertThat(invalidFields(new CreateLoanRequest(null, null))).containsExactlyInAnyOrder("userId", "bookId");
        assertThat(invalidFields(new CreateLoanRequest(0L, -5L))).containsExactlyInAnyOrder("userId", "bookId");
    }
}