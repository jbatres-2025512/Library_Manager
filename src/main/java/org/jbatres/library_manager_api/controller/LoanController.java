package org.jbatres.library_manager_api.controller;

import org.jbatres.library_manager_api.dto.request.CreateLoanRequest;
import org.jbatres.library_manager_api.dto.response.LoanResponse;
import org.jbatres.library_manager_api.dto.response.PageResponse;
import org.jbatres.library_manager_api.security.AuthenticatedUser;
import org.jbatres.library_manager_api.service.LoanService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/prestamos")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    /** Route-level rules live in SecurityConfig; @PreAuthorize is the second layer. */
    @PostMapping
    @PreAuthorize("hasAnyRole('BIBLIOTECARIO', 'ADMIN')")
    public ResponseEntity<LoanResponse> createLoan(@Valid @RequestBody CreateLoanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(loanService.createLoan(request));
    }

    @PatchMapping("/{id}/devolucion")
    @PreAuthorize("hasAnyRole('BIBLIOTECARIO', 'ADMIN')")
    public ResponseEntity<LoanResponse> returnLoan(@PathVariable("id") Long id) {
        return ResponseEntity.ok(loanService.returnLoan(id));
    }

    /** ?page=0&size=20&sort=loanDate,desc. The reader id comes from the JWT, never from the URL. */
    @GetMapping("/mis-prestamos")
    @PreAuthorize("hasRole('LECTOR')")
    public ResponseEntity<PageResponse<LoanResponse>> myLoans(
            @AuthenticationPrincipal AuthenticatedUser principal, Pageable pageable) {
        return ResponseEntity.ok(loanService.getMyLoans(principal.getId(), pageable));
    }

    /** ?page=0&size=20&sort=expectedReturnDate,asc */
    @GetMapping("/atrasados")
    @PreAuthorize("hasAnyRole('BIBLIOTECARIO', 'ADMIN')")
    public ResponseEntity<PageResponse<LoanResponse>> overdueLoans(Pageable pageable) {
        return ResponseEntity.ok(loanService.getOverdueLoans(pageable));
    }
}