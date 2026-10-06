package org.jbatres.library_manager_api.controller;

import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.repository.BookRepository;
import org.jbatres.library_manager_api.security.AuthenticatedUser;
import org.jbatres.library_manager_api.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real endpoints against the real PostgreSQL database (schema.sql + data.sql), through MockMvc.
 * @Transactional: every test is rolled back, so the seed data is never modified.
 * Needs the same environment variables as the app (DB_PASSWORD, JWT_SECRET).
 * Seed books used: Effective Java (5 total, 4 available, 1 on loan) and Clean Code (4 of 4, no loans).
 * Note: after a database constraint violation PostgreSQL aborts the test transaction, so the
 * duplicate-ISBN tests do not query the database afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookControllerIntegrationTest {

    private static final String BASE = "/api/v1/libros";
    private static final String ISBN_EFFECTIVE_JAVA = "9780134685991";
    private static final String ISBN_CLEAN_CODE = "9780132350884";
    private static final String NEW_ISBN = "9780306406157";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private BookRepository bookRepository;

    private String bearer(Role role) {
        return "Bearer " + jwtService.generateToken(
                new AuthenticatedUser(999_999L, role.name().toLowerCase() + "@test.com", role, null));
    }

    private ResultActions as(Role role, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, bearer(role)));
    }

    private ResultActions send(Role role, MockHttpServletRequestBuilder builder, String json) throws Exception {
        return as(role, builder.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long idOf(String isbn) {
        return bookRepository.findByIsbn(isbn).orElseThrow().getId();
    }

    private String bookJson(String isbn, String title, int totalStock) {
        return """
                { "isbn": "%s", "title": "%s", "author": "Some Author", "category": "Programming", "totalStock": %d }
                """.formatted(isbn, title, totalStock);
    }

    // ---------- list ----------

    @Test
    void list_returnsPagedCatalog_forAnyAuthenticatedRole() throws Exception {
        as(Role.LECTOR, get(BASE).param("size", "3").param("sort", "title"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.page.size").value(3))
                .andExpect(jsonPath("$.page.totalElements").value(10))
                .andExpect(jsonPath("$.page.totalPages").value(4));
    }

    @Test
    void list_filtersByTitleAndCategory() throws Exception {
        as(Role.LECTOR, get(BASE).param("title", "design").param("category", "programming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void list_blankFilters_areIgnored() throws Exception {
        as(Role.LECTOR, get(BASE).param("title", "").param("category", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(10));
    }

    @Test
    void list_unknownSortField_returns400() throws Exception {
        as(Role.LECTOR, get(BASE).param("sort", "password,asc"))
                .andExpect(status().isBadRequest());
    }

    // ---------- get ----------

    @Test
    void get_returnsBookDetail() throws Exception {
        as(Role.LECTOR, get(BASE + "/" + idOf(ISBN_EFFECTIVE_JAVA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isbn").value(ISBN_EFFECTIVE_JAVA))
                .andExpect(jsonPath("$.totalStock").value(5))
                .andExpect(jsonPath("$.availableStock").value(4));
    }

    @Test
    void get_unknownBook_returns404() throws Exception {
        as(Role.LECTOR, get(BASE + "/999999")).andExpect(status().isNotFound());
    }

    // ---------- create ----------

    @Test
    void create_asAdmin_returns201WithLocationAndFullStockAvailable() throws Exception {
        send(Role.ADMIN, post(BASE), bookJson(NEW_ISBN, "New Book", 3))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(BASE + "/" + idOfNewBook())))
                .andExpect(jsonPath("$.isbn").value(NEW_ISBN))
                .andExpect(jsonPath("$.totalStock").value(3))
                .andExpect(jsonPath("$.availableStock").value(3));
    }

    private long idOfNewBook() {
        return bookRepository.findByIsbn(NEW_ISBN).map(b -> b.getId()).orElse(-1L);
    }

    @Test
    void create_duplicateIsbn_returns409() throws Exception {
        send(Role.ADMIN, post(BASE), bookJson(ISBN_EFFECTIVE_JAVA, "Copy", 1))
                .andExpect(status().isConflict());
    }

    @Test
    void create_invalidBody_returns400() throws Exception {
        send(Role.ADMIN, post(BASE), bookJson("ABC", "New Book", -1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_asReaderOrLibrarian_returns403() throws Exception {
        send(Role.LECTOR, post(BASE), bookJson(NEW_ISBN, "New Book", 1)).andExpect(status().isForbidden());
        send(Role.BIBLIOTECARIO, post(BASE), bookJson(NEW_ISBN, "New Book", 1)).andExpect(status().isForbidden());
    }

    // ---------- update ----------

    @Test
    void update_shiftsAvailableStockByTheTotalStockDelta() throws Exception {
        long id = idOf(ISBN_EFFECTIVE_JAVA); // 5 total, 4 available (1 on loan)

        send(Role.ADMIN, put(BASE + "/" + id), bookJson(ISBN_EFFECTIVE_JAVA, "Effective Java 4th Ed", 8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Effective Java 4th Ed"))
                .andExpect(jsonPath("$.totalStock").value(8))
                .andExpect(jsonPath("$.availableStock").value(7));
    }

    @Test
    void update_totalBelowCopiesOnLoan_returns400AndChangesNothing() throws Exception {
        long id = idOf(ISBN_EFFECTIVE_JAVA);

        send(Role.ADMIN, put(BASE + "/" + id), bookJson(ISBN_EFFECTIVE_JAVA, "Effective Java", 0))
                .andExpect(status().isBadRequest());

        as(Role.ADMIN, get(BASE + "/" + id))
                .andExpect(jsonPath("$.totalStock").value(5))
                .andExpect(jsonPath("$.availableStock").value(4));
    }

    @Test
    void update_unknownBook_returns404() throws Exception {
        send(Role.ADMIN, put(BASE + "/999999"), bookJson(NEW_ISBN, "Ghost", 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_isbnOfAnotherBook_returns409() throws Exception {
        long cleanCodeId = idOf(ISBN_CLEAN_CODE);

        send(Role.ADMIN, put(BASE + "/" + cleanCodeId), bookJson(ISBN_EFFECTIVE_JAVA, "Clean Code", 4))
                .andExpect(status().isConflict());
    }

    @Test
    void update_asReader_returns403() throws Exception {
        send(Role.LECTOR, put(BASE + "/" + idOf(ISBN_CLEAN_CODE)), bookJson(ISBN_CLEAN_CODE, "Clean Code", 4))
                .andExpect(status().isForbidden());
    }

    // ---------- delete ----------

    @Test
    void delete_bookWithoutLoans_returns204_andDisappearsFromCatalog() throws Exception {
        long id = idOf(ISBN_CLEAN_CODE); // 4 of 4 available

        as(Role.ADMIN, delete(BASE + "/" + id)).andExpect(status().isNoContent());

        as(Role.ADMIN, get(BASE + "/" + id)).andExpect(status().isNotFound());
        as(Role.ADMIN, get(BASE)).andExpect(jsonPath("$.page.totalElements").value(9));
    }

    @Test
    void delete_bookWithCopiesOnLoan_returns400() throws Exception {
        as(Role.ADMIN, delete(BASE + "/" + idOf(ISBN_EFFECTIVE_JAVA))).andExpect(status().isBadRequest());
    }

    @Test
    void delete_unknownBook_returns404() throws Exception {
        as(Role.ADMIN, delete(BASE + "/999999")).andExpect(status().isNotFound());
    }

    @Test
    void delete_asLibrarian_returns403() throws Exception {
        as(Role.BIBLIOTECARIO, delete(BASE + "/" + idOf(ISBN_CLEAN_CODE))).andExpect(status().isForbidden());
    }

    @Test
    void deletedBookIsbn_cannotBeReused_returns409() throws Exception {
        as(Role.ADMIN, delete(BASE + "/" + idOf(ISBN_CLEAN_CODE))).andExpect(status().isNoContent());

        send(Role.ADMIN, post(BASE), bookJson(ISBN_CLEAN_CODE, "Clean Code Reprint", 1))
                .andExpect(status().isConflict());
    }
}