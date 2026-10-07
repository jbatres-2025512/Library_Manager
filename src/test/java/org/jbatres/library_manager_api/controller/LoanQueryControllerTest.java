package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.entity.Book;
import org.jbatres.library_manager_api.entity.Loan;
import org.jbatres.library_manager_api.entity.LoanStatus;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.security.AuthenticatedUser;
import org.jbatres.library_manager_api.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level checks of the two GET endpoints with real tokens (no Phase 12 handler needed). */
@AutoConfigureMockMvc
class LoanQueryControllerTest extends AbstractLoanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    private final LocalDate today = LocalDate.now();

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole(), null));
    }

    private Loan saveLoan(User user, Book book, LocalDate loanDate, LoanStatus status) {
        return loanRepository.save(Loan.builder()
                .user(user).book(book)
                .loanDate(loanDate).expectedReturnDate(loanDate.plusDays(14))
                .actualReturnDate(status == LoanStatus.DEVUELTO ? loanDate.plusDays(3) : null)
                .status(status).build());
    }

    @Test
    void misPrestamos_returnsOnlyTheCallersLoans() throws Exception {
        User a = newReader();
        User b = newReader();
        saveLoan(a, newBook(2, 1), today.minusDays(3), LoanStatus.ACTIVO);
        saveLoan(a, newBook(2, 1), today.minusDays(2), LoanStatus.ACTIVO);
        saveLoan(b, newBook(2, 1), today.minusDays(1), LoanStatus.ACTIVO);

        mockMvc.perform(get("/api/v1/prestamos/mis-prestamos").header(HttpHeaders.AUTHORIZATION, bearer(a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].userId").value(a.getId().intValue()))
                .andExpect(jsonPath("$.content[1].userId").value(a.getId().intValue()))
                .andExpect(jsonPath("$.content[0].bookTitle").isNotEmpty())
                .andExpect(jsonPath("$.content[0].status").value("ACTIVO"));
    }

    @Test
    void misPrestamos_supportsPageAndSizeParameters() throws Exception {
        User reader = newReader();
        for (int i = 1; i <= 3; i++) {
            saveLoan(reader, newBook(2, 1), today.minusDays(i), LoanStatus.ACTIVO);
        }

        mockMvc.perform(get("/api/v1/prestamos/mis-prestamos")
                        .param("page", "1").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void atrasados_asLibrarian_includesPastDueLoanWithOverdueFlag() throws Exception {
        User librarian = newUser(Role.BIBLIOTECARIO, UserStatus.ACTIVO);
        Loan overdue = saveLoan(newReader(), newBook(2, 1), today.minusDays(20), LoanStatus.ACTIVO);

        mockMvc.perform(get("/api/v1/prestamos/atrasados")
                        .param("size", "100")
                        .header(HttpHeaders.AUTHORIZATION, bearer(librarian)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + overdue.getId() + ")]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.id == " + overdue.getId() + ")].overdue").value(true));
    }

    @Test
    void atrasados_asReader_isForbidden_andMisPrestamosAsLibrarianToo() throws Exception {
        User reader = newReader();
        User librarian = newUser(Role.BIBLIOTECARIO, UserStatus.ACTIVO);

        mockMvc.perform(get("/api/v1/prestamos/atrasados").header(HttpHeaders.AUTHORIZATION, bearer(reader)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/prestamos/mis-prestamos").header(HttpHeaders.AUTHORIZATION, bearer(librarian)))
                .andExpect(status().isForbidden());
    }

    @Test
    void queries_withoutToken_areUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/prestamos/mis-prestamos")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/prestamos/atrasados")).andExpect(status().isUnauthorized());
    }
}