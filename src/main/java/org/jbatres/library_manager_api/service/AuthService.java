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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Registration and login.
 * Intentionally NOT @Transactional: BCrypt is CPU-heavy and must not run while a database
 * connection is held (HikariCP pool pressure under load). Each repository call is already
 * its own short transaction, and the UNIQUE constraint on users.email is the final arbiter.
 */
@Service
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       UserMapper userMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
    }

    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email is already registered");
        }

        User user = User.builder()
                .name(request.name().trim())
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVO)
                .role(Role.LECTOR) // fixed by the business rule; never read from the request
                .build();

        try {
            User saved = userRepository.saveAndFlush(user);
            log.info("User registered: id={}, role={}", saved.getId(), saved.getRole());
            return userMapper.toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // Concurrent registration with the same email: uk_users_email rejected the insert
            throw new DuplicateResourceException("Email is already registered");
        }
    }

    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (AuthenticationException e) {
            log.warn("Failed login attempt for email={}", email);
            throw new InvalidCredentialsException();
        }

        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

        // One primary-key lookup, after BCrypt, to build the UserResponse (name and status are
        // not in the principal). A user removed in between is treated as invalid credentials.
        User user = userRepository.findById(principal.getId())
                .orElseThrow(InvalidCredentialsException::new);

        String token = jwtService.generateToken(principal);
        log.info("Login successful: userId={}", user.getId());

        return new LoginResponse(
                token,
                TOKEN_TYPE,
                jwtService.getExpirationSeconds(),
                userMapper.toResponse(user));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}