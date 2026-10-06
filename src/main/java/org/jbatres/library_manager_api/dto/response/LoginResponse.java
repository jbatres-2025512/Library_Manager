package org.jbatres.library_manager_api.dto.response;

/** Response of POST /api/v1/auth/login. Use as header: Authorization: Bearer <token>. */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        UserResponse user) {
}