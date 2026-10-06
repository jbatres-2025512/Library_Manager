package org.jbatres.library_manager_api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.repository.UserRepository;
import org.jbatres.library_manager_api.security.AuthenticatedUser;
import org.jbatres.library_manager_api.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real endpoints against the real PostgreSQL database (schema.sql + data.sql), through MockMvc.
 * @Transactional: every test is rolled back, so the seed data is never modified.
 * Needs the same environment variables as the app (DB_PASSWORD, JWT_SECRET).
 * Seed users and passwords come from data.sql.
 * ADAPT: TOKEN_FIELD must match the token property name of LoginResponse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String TOKEN_FIELD = "token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String registerJson(String name, String email, String password) {
        return """
                { "name": "%s", "email": "%s", "password": "%s" }
                """.formatted(name, email, password);
    }

    private String loginJson(String email, String password) {
        return """
                { "email": "%s", "password": "%s" }
                """.formatted(email, password);
    }

    private String loginAndGetToken(String email, String password) throws Exception {
        String body = postJson(LOGIN, loginJson(email, password))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).path(TOKEN_FIELD).asText();
        assertThat(token).isNotBlank();
        return token;
    }

    // ---------- register ----------

    @Test
    void register_validRequest_returns201WithLectorRoleAndNoPassword() throws Exception {
        postJson(REGISTER, registerJson("Test Reader", "New.Reader@Test.com", "Secret123!"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("new.reader@test.com"))
                .andExpect(jsonPath("$.role").value("LECTOR"))
                .andExpect(jsonPath("$.status").value("ACTIVO"))
                .andExpect(jsonPath("$.password").doesNotExist());

        User stored = userRepository.findByEmail("new.reader@test.com").orElseThrow();
        assertThat(stored.getPassword()).startsWith("$2").isNotEqualTo("Secret123!"); // BCrypt hash
    }

    @Test
    void register_ignoresRoleSentByClient() throws Exception {
        postJson(REGISTER, """
                { "name": "Hacker", "email": "hacker@test.com", "password": "Secret123!", "role": "ADMIN" }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("LECTOR"));

        assertThat(userRepository.findByEmail("hacker@test.com").orElseThrow().getRole()).isEqualTo(Role.LECTOR);
    }

    @Test
    void register_duplicateEmail_returns409_evenWithDifferentCasing() throws Exception {
        postJson(REGISTER, registerJson("First", "dup@test.com", "Secret123!"))
                .andExpect(status().isCreated());

        postJson(REGISTER, registerJson("Second", "DUP@Test.com", "Secret123!"))
                .andExpect(status().isConflict());
    }

    @Test
    void register_emailOfSeededUser_returns409() throws Exception {
        postJson(REGISTER, registerJson("Fake Admin", "admin@library.com", "Secret123!"))
                .andExpect(status().isConflict());
    }

    @Test
    void register_invalidBody_returns400AndPersistsNothing() throws Exception {
        postJson(REGISTER, registerJson("", "not-an-email", "short"))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.existsByEmail("not-an-email")).isFalse();
    }

    @Test
    void register_malformedJson_returns400() throws Exception {
        postJson(REGISTER, "{ not json").andExpect(status().isBadRequest());
    }

    // ---------- login ----------

    @ParameterizedTest(name = "{0} logs in as {2}")
    @CsvSource({
            "admin@library.com,     Admin123!,     ADMIN",
            "librarian@library.com, Librarian123!, BIBLIOTECARIO",
            "reader1@library.com,   Reader123!,    LECTOR",
            "reader4@library.com,   Reader123!,    LECTOR" // SANCIONADO users can authenticate
    })
    void login_seededUsers_returnTokenWithTheirRole(String email, String password, String role) throws Exception {
        String token = loginAndGetToken(email, password);

        AuthenticatedUser parsed = jwtService.parseToken(token);
        assertThat(parsed.getEmail()).isEqualTo(email);
        assertThat(parsed.getRole()).isEqualTo(Role.valueOf(role));
    }

    @Test
    void login_emailIsCaseInsensitive() throws Exception {
        loginAndGetToken("READER1@LIBRARY.COM", "Reader123!");
    }

    @Test
    void login_wrongPasswordAndUnknownEmail_areIndistinguishable() throws Exception {
        var wrongPassword = postJson(LOGIN, loginJson("reader1@library.com", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();
        var unknownEmail = postJson(LOGIN, loginJson("nobody@library.com", "Reader123!"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        // No user enumeration: same status and same body for both failures
        assertThat(unknownEmail.getStatus()).isEqualTo(wrongPassword.getStatus());
        assertThat(unknownEmail.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
    }

    @Test
    void login_missingFields_returns400() throws Exception {
        postJson(LOGIN, "{}").andExpect(status().isBadRequest());
    }

    // ---------- full flow ----------

    @Test
    void registerThenLogin_tokenIsUsableAndRespectsReaderPermissions() throws Exception {
        String body = postJson(REGISTER, registerJson("Flow Reader", "flow@test.com", "Secret123!"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long userId = objectMapper.readTree(body).path("id").asLong();

        String token = loginAndGetToken("flow@test.com", "Secret123!");
        AuthenticatedUser parsed = jwtService.parseToken(token);
        assertThat(parsed.getId()).isEqualTo(userId);
        assertThat(parsed.getRole()).isEqualTo(Role.LECTOR);

        String bearer = "Bearer " + token;

        // Authenticated catalog read: not rejected by security
        int catalogStatus = mockMvc.perform(get("/api/v1/libros").header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn().getResponse().getStatus();
        assertThat(catalogStatus).isNotIn(401, 403);

        // A reader can never create books
        mockMvc.perform(post("/api/v1/libros")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}