package org.jbatres.library_manager_api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * Body of POST /api/v1/auth/register. There is deliberately NO role field: every
 * self-registered user is a LECTOR, so a client cannot escalate its own privileges.
 * Name and email are normalized (trim / lower-case) so the DB constraint email = lower(email) holds.
 */
public record RegisterRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must not exceed 100 characters")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 150, message = "Email must not exceed 150 characters")
        String email,

        // BCrypt only uses the first 72 bytes, so longer passwords are rejected instead of silently truncated.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password) {

    public RegisterRequest {
        name = name == null ? null : name.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Never print the password (logs, exceptions). */
    @Override
    public String toString() {
        return "RegisterRequest[name=" + name + ", email=" + email + ", password=***]";
    }
}