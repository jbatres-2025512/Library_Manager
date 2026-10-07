package org.jbatres.library_manager_api.exception;

import org.jbatres.library_manager_api.dto.request.RegisterRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Handler tests with a throw-away controller: no Spring context, no database, no environment
 * variables. Verifies HTTP status, error code, message and that internals are never leaked.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @RestController
    @RequestMapping("/test")
    static class ThrowingController {
        @GetMapping("/not-found")
        String notFound() { throw new ResourceNotFoundException("Book not found with id 5"); }

        @GetMapping("/business")
        String business() { throw new BusinessRuleException("Reader cannot have more than 3 active loans"); }

        @GetMapping("/sanctioned")
        String sanctioned() { throw new UserSanctionedException("User is sanctioned and cannot borrow books"); }

        @GetMapping("/already-returned")
        String alreadyReturned() { throw new LoanAlreadyReturnedException("Loan 9 has already been returned"); }

        @GetMapping("/duplicate")
        String duplicate() { throw new DuplicateResourceException("Email already registered"); }

        @GetMapping("/integrity")
        String integrity() { throw new DataIntegrityViolationException("duplicate key value violates constraint uk_books_isbn"); }

        @GetMapping("/lock")
        String lock() { throw new CannotAcquireLockException("canceling statement due to lock timeout"); }

        @GetMapping("/pool")
        String pool() { throw new CannotCreateTransactionException("HikariPool-1 - Connection is not available"); }

        @GetMapping("/denied")
        String denied() { throw new AccessDeniedException("Access Denied"); }

        @GetMapping("/boom")
        String boom() { throw new IllegalStateException("secret internal detail"); }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody RegisterRequest body) { return "ok"; }

        @GetMapping("/typed/{id}")
        String typed(@PathVariable Long id) { return "ok"; }
    }

    @Test
    void resourceNotFound_is404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Book not found with id 5"))
                .andExpect(jsonPath("$.path").value("/test/not-found"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void businessRule_is400_andSanctionIsABusinessRuleToo() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("Reader cannot have more than 3 active loans"));
        mockMvc.perform(get("/test/sanctioned"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void loanAlreadyReturned_is409_notTheGenericBusiness400() throws Exception {
        mockMvc.perform(get("/test/already-returned"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void duplicateResource_is409() throws Exception {
        mockMvc.perform(get("/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void dataIntegrityViolation_is409_withoutLeakingConstraintNames() throws Exception {
        mockMvc.perform(get("/test/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(not(containsString("uk_books_isbn"))));
    }

    @Test
    void lockTimeout_is409Retryable() throws Exception {
        mockMvc.perform(get("/test/lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void exhaustedPool_is503WithRetryAfter_withoutLeakingInternals() throws Exception {
        mockMvc.perform(get("/test/pool"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(not(containsString("Hikari"))));
    }

    @Test
    void accessDenied_is403() throws Exception {
        mockMvc.perform(get("/test/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void unexpectedException_is500_withReferenceAndNoInternals() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("Reference:")))
                .andExpect(jsonPath("$.message").value(not(containsString("secret internal detail"))));
    }

    @Test
    void invalidBody_is400_withOneEntryPerInvalidField() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"bad\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").isNotEmpty());
    }

    @Test
    void malformedJson_is400_withoutEchoingParserDetails() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable request body"));
    }

    @Test
    void wrongPathVariableType_is400() throws Exception {
        mockMvc.perform(get("/test/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/test/typed/abc"));
    }

    @Test
    void unsupportedHttpMethod_is405WithJsonBody() throws Exception {
        mockMvc.perform(post("/test/typed/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaType_is415WithJsonBody() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}