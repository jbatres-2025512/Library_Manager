package org.jbatres.library_manager_api.exception;

import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.security.AuthenticatedUser;
import org.jbatres.library_manager_api.security.JwtService;
import org.jbatres.library_manager_api.service.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real controllers + services + security + handler together. Uses the real database (own rows
 * only, cleaned by AbstractLoanIntegrationTest) and needs DB_PASSWORD / JWT_SECRET.
 */
@AutoConfigureMockMvc
class ErrorHandlingIntegrationTest extends AbstractLoanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole(), null));
    }

    private User librarian() {
        return newUser(Role.BIBLIOTECARIO, UserStatus.ACTIVO);
    }

    @Test
    void createLoan_withInvalidBody_is400ValidationErrorListingBothFields() throws Exception {
        mockMvc.perform(post("/api/v1/prestamos")
                        .header(HttpHeaders.AUTHORIZATION, bearer(librarian()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'userId')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'bookId')]").isNotEmpty());
    }

    @Test
    void createLoan_withMalformedJson_is400() throws Exception {
        mockMvc.perform(post("/api/v1/prestamos")
                        .header(HttpHeaders.AUTHORIZATION, bearer(librarian()))
                        .contentType(MediaType.APPLICATION_JSON).content("{userId:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void createLoan_unknownBook_is404() throws Exception {
        User reader = newReader();
        mockMvc.perform(post("/api/v1/prestamos")
                        .header(HttpHeaders.AUTHORIZATION, bearer(librarian()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + reader.getId() + ",\"bookId\":999999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/prestamos"));
    }

    @Test
    void createLoan_bookWithoutCopies_is400BusinessRule() throws Exception {
        User reader = newReader();
        Book book = newBook(1, 0);
        mockMvc.perform(post("/api/v1/prestamos")
                        .header(HttpHeaders.AUTHORIZATION, bearer(librarian()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + reader.getId() + ",\"bookId\":" + book.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value(containsString("no available copies")));
    }

    @Test
    void returnLoan_twice_firstIs200_secondIs409() throws Exception {
        User reader = newReader();
        LoanResponse loan = borrow(reader, newBook(2, 2));
        String token = bearer(librarian());

        mockMvc.perform(patch("/api/v1/prestamos/{id}/devolucion", loan.id())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEVUELTO"));

        mockMvc.perform(patch("/api/v1/prestamos/{id}/devolucion", loan.id())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void returnLoan_unknownId_is404_andNonNumericId_is400() throws Exception {
        String token = bearer(librarian());
        mockMvc.perform(patch("/api/v1/prestamos/999999999/devolucion").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/prestamos/abc/devolucion").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void myLoans_withUnsupportedSort_is400_notAnInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/prestamos/mis-prestamos").param("sort", "user.password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(newReader())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value(containsString("Unsupported sort property")));
    }

    @Test
    void unknownUrl_isJson404_andWrongMethodIsJson405() throws Exception {
        String token = bearer(newReader());
        mockMvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/prestamos").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void securityErrors_keepTheSameJsonShape() throws Exception {
        mockMvc.perform(get("/api/v1/prestamos/atrasados"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/prestamos/atrasados").header(HttpHeaders.AUTHORIZATION, bearer(newReader())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}