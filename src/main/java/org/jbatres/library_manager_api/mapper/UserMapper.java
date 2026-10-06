package org.jbatres.library_manager_api.mapper;

import org.jbatres.library_manager_api.dto.response.UserResponse;
import org.jbatres.library_manager_api.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus());
    }
}