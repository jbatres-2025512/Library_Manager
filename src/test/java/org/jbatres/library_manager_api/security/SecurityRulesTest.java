package org.jbatres.library_manager_api.security;

import org.jbatres.library_manager_api.entity.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the access matrix of SecurityConfig without logging in: tokens are minted with JwtService.
 * "ALLOWED" means "not rejected by security" (status other than 401/403), so these tests keep
 * passing when the real controllers are added in later phases.
 * All ids in the paths do not exist on purpose, so later phases can never modify real data here.
 * Needs the same environment variables as the app (DB_PASSWORD, JWT_SECRET).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private String bearer(Role role) {
        AuthenticatedUser user = new AuthenticatedUser(
                999_999L, role.name().toLowerCase() + "@test.com", role, null);
        return "Bearer " + jwtService.generateToken(user);
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            // public endpoints (POST only)
            "POST,/api/v1/auth/login,NONE,ALLOWED",
            "POST,/api/v1/auth/register,NONE,ALLOWED",
            "GET,/api/v1/auth/login,NONE,UNAUTHORIZED",
            // books: read = any authenticated user
            "GET,/api/v1/libros,NONE,UNAUTHORIZED",
            "GET,/api/v1/libros,LECTOR,ALLOWED",
            "GET,/api/v1/libros,BIBLIOTECARIO,ALLOWED",
            "GET,/api/v1/libros,ADMIN,ALLOWED",
            "GET,/api/v1/libros/999999,LECTOR,ALLOWED",
            // books: write = ADMIN only
            "POST,/api/v1/libros,NONE,UNAUTHORIZED",
            "POST,/api/v1/libros,LECTOR,FORBIDDEN",
            "POST,/api/v1/libros,BIBLIOTECARIO,FORBIDDEN",
            "POST,/api/v1/libros,ADMIN,ALLOWED",
            "PUT,/api/v1/libros/999999,LECTOR,FORBIDDEN",
            "PUT,/api/v1/libros/999999,ADMIN,ALLOWED",
            "DELETE,/api/v1/libros/999999,BIBLIOTECARIO,FORBIDDEN",
            "DELETE,/api/v1/libros/999999,ADMIN,ALLOWED",
            // loans: create / return = BIBLIOTECARIO, ADMIN
            "POST,/api/v1/prestamos,NONE,UNAUTHORIZED",
            "POST,/api/v1/prestamos,LECTOR,FORBIDDEN",
            "POST,/api/v1/prestamos,BIBLIOTECARIO,ALLOWED",
            "POST,/api/v1/prestamos,ADMIN,ALLOWED",
            "PATCH,/api/v1/prestamos/999999/devolucion,LECTOR,FORBIDDEN",
            "PATCH,/api/v1/prestamos/999999/devolucion,BIBLIOTECARIO,ALLOWED",
            "PATCH,/api/v1/prestamos/999999/devolucion,ADMIN,ALLOWED",
            // loans: my loans = LECTOR only
            "GET,/api/v1/prestamos/mis-prestamos,NONE,UNAUTHORIZED",
            "GET,/api/v1/prestamos/mis-prestamos,LECTOR,ALLOWED",
            "GET,/api/v1/prestamos/mis-prestamos,BIBLIOTECARIO,FORBIDDEN",
            "GET,/api/v1/prestamos/mis-prestamos,ADMIN,FORBIDDEN",
            // loans: overdue = BIBLIOTECARIO, ADMIN
            "GET,/api/v1/prestamos/atrasados,LECTOR,FORBIDDEN",
            "GET,/api/v1/prestamos/atrasados,BIBLIOTECARIO,ALLOWED",
            "GET,/api/v1/prestamos/atrasados,ADMIN,ALLOWED",
            // anything else needs authentication
            "GET,/api/v1/unknown,NONE,UNAUTHORIZED"
    })
    void accessMatrix(String method, String path, String role, String expectation) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (!"NONE".equals(role)) {
            builder.header(HttpHeaders.AUTHORIZATION, bearer(Role.valueOf(role)));
        }

        int status = mockMvc.perform(builder).andReturn().getResponse().getStatus();

        switch (expectation) {
            case "ALLOWED" -> assertThat(status).isNotIn(401, 403);
            case "FORBIDDEN" -> assertThat(status).isEqualTo(403);
            case "UNAUTHORIZED" -> assertThat(status).isEqualTo(401);
            default -> throw new IllegalArgumentException(expectation);
        }
    }

    @Test
    void missingToken_returnsJsonErrorBody() throws Exception {
        mockMvc.perform(get("/api/v1/libros"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/v1/libros"));
    }

    @Test
    void garbageToken_returns401WithInvalidTokenMessage() throws Exception {
        mockMvc.perform(get("/api/v1/libros").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid token"));
    }

    @Test
    void nonBearerScheme_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/libros").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46YWRtaW4="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongRole_returnsJsonForbiddenBody() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/v1/libros")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.LECTOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }
}