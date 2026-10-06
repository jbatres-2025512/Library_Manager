package org.jbatres.library_manager_api.security;

import org.jbatres.library_manager_api.entity.Role;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Security principal. Used in two situations:
 * <ul>
 *   <li>login: built from the database (password hash present) so the AuthenticationManager can verify it;</li>
 *   <li>every other request: built from the signed JWT claims (password = null), so no database
 *       lookup is needed per request. Controllers receive it with @AuthenticationPrincipal.</li>
 * </ul>
 * No toString on purpose that would include the password hash.
 */
public class AuthenticatedUser implements UserDetails, CredentialsContainer {

    private final Long id;
    private final String email;
    private final Role role;
    private String password;

    public AuthenticatedUser(Long id, String email, Role role, String password) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.password = password;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        this.password = null;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + ", email=" + email + ", role=" + role + "]";
    }
}