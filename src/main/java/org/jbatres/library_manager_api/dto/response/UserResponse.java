package org.jbatres.library_manager_api.dto.response;

import org.jbatres.library_manager_api.entity.Role;
import org.jbatres.library_manager_api.entity.UserStatus;

/** Public view of a user. Never includes the password hash. */
public record UserResponse(
        Long id,
        String name,
        String email,
        Role role,
        UserStatus status) {
}