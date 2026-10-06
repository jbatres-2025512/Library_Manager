package org.jbatres.library_manager_api.service;

import org.jbatres.library_manager_api.dto.request.LoginRequest;
import org.jbatres.library_manager_api.dto.request.RegisterRequest;
import org.jbatres.library_manager_api.dto.response.LoginResponse;
import org.jbatres.library_manager_api.dto.response.UserResponse;
import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.User;
import org.jbatres.library_manager_api.entity.UserStatus;
import org.jbatres.library_manager_api.exception.DuplicateResourceException;
import org.jbatres.library_manager_api.exception.InvalidCredentialsException;
import org.jbatres.library_manager_api.mapper.UserMapper;
import org.jbatres.library_manager_api.repository.UserRepository;
import org.jbatres.library_manager_api.security.AuthenticatedUser;
import org.jbatres.library_manager_api.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (Mockito): no Spring context and no database.
 * JwtService is real (fixed clock) so the issued token is validated end to end.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "unit-test-secret-key-with-at-least-32-bytes!!";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;

    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 3_600_000L, FIXED_CLOCK);
        authService = new AuthService(
                userRepository, passwordEncoder, authenticationManager, jwtService, new UserMapper());
    }

    // ---------- register ----------

    @Test
    void register_createsActiveReaderWithEncodedPasswordAndNormalizedEmail() {
        when(userRepository.existsByEmail("ana@library.com")).thenReturn(false);
        when(passwordEncoder.encode("Secret123!")).thenReturn("HASH");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(10L);
            return user;
        });

        UserResponse response = authService.register(
                new RegisterRequest("  Ana Lopez ", " Ana@Library.COM ", "Secret123!"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@library.com");
        assertThat(saved.getValue().getName()).isEqualTo("Ana Lopez");
        assertThat(saved.getValue().getPassword()).isEqualTo("HASH");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.LECTOR);
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVO);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.role()).isEqualTo(Role.LECTOR);
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVO);
        assertThat(response.toString()).doesNotContain("HASH").doesNotContain("Secret123!");
    }

    @Test
    void register_existingEmail_isRejectedBeforeEncodingOrSaving() {
        when(userRepository.existsByEmail("ana@library.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Ana", "ana@library.com", "Secret123!")))
                .isInstanceOf(DuplicateResourceException.class);

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_concurrentDuplicate_isMappedToDuplicateResource() {
        when(userRepository.existsByEmail("ana@library.com")).thenReturn(false);
        when(passwordEncoder.encode("Secret123!")).thenReturn("HASH");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Ana", "ana@library.com", "Secret123!")))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ---------- login ----------

    @Test
    void login_returnsTokenAndUserDetails() {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "reader@library.com", Role.LECTOR, null);
        User stored = User.builder().id(7L).name("Reader One").email("reader@library.com").password("HASH")
                .role(Role.LECTOR).status(UserStatus.SANCIONADO).build();
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(stored));

        LoginResponse response = authService.login(new LoginRequest("READER@library.com", "Secret123!"));

        ArgumentCaptor<Authentication> sent = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(sent.capture());
        assertThat(sent.getValue().getName()).isEqualTo("reader@library.com"); // normalized
        assertThat(sent.getValue().getCredentials()).isEqualTo("Secret123!");

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(3600);
        assertThat(response.user().id()).isEqualTo(7L);
        assertThat(response.user().name()).isEqualTo("Reader One");
        assertThat(response.user().role()).isEqualTo(Role.LECTOR);
        assertThat(response.user().status()).isEqualTo(UserStatus.SANCIONADO); // sanctioned users can log in
        assertThat(response.toString()).doesNotContain("HASH");

        AuthenticatedUser parsed = jwtService.parseToken(response.token());
        assertThat(parsed.getId()).isEqualTo(7L);
        assertThat(parsed.getEmail()).isEqualTo("reader@library.com");
        assertThat(parsed.getRole()).isEqualTo(Role.LECTOR);
    }

    @Test
    void login_userRemovedAfterAuthentication_isInvalidCredentials() {
        AuthenticatedUser principal = new AuthenticatedUser(7L, "reader@library.com", Role.LECTOR, null);
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("reader@library.com", "Secret123!")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_badCredentials_isMappedToGenericInvalidCredentials() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("reader@library.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void login_anyOtherAuthenticationFailure_isAlsoMappedToInvalidCredentials() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("reader@library.com", "Secret123!")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}