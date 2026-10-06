package org.jbatres.library_manager_api.security;

import org.jbatres.library_manager_api.security.JwtAuthenticationFilter;
import org.jbatres.library_manager_api.security.JwtService;
import org.jbatres.library_manager_api.security.RestAccessDeniedHandler;
import org.jbatres.library_manager_api.security.RestAuthenticationEntryPoint;
import org.jbatres.library_manager_api.security.UserDetailsServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. Authorization is declared per route here (single place to audit the
 * access matrix) and @EnableMethodSecurity allows an additional @PreAuthorize layer on controllers.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String LIBRARIAN = "BIBLIOTECARIO";
    private static final String READER = "LECTOR";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Public
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // Needed so unhandled errors are not masked as 401/403 by the /error dispatch
                        .requestMatchers("/error").permitAll()
                        // Books
                        .requestMatchers(HttpMethod.GET, "/api/v1/libros", "/api/v1/libros/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/libros").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/libros/*").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/libros/*").hasRole(ADMIN)
                        // Loans
                        .requestMatchers(HttpMethod.POST, "/api/v1/prestamos").hasAnyRole(LIBRARIAN, ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/prestamos/*/devolucion").hasAnyRole(LIBRARIAN, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/prestamos/mis-prestamos").hasRole(READER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/prestamos/atrasados").hasAnyRole(LIBRARIAN, ADMIN)
                        // Anything else requires a valid token
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsServiceImpl userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider authenticationProvider) {
        return new ProviderManager(authenticationProvider);
    }
}