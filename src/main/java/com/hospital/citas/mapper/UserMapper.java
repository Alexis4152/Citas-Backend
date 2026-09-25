package com.hospital.citas.mapper;

import com.hospital.citas.dto.response.SpecialtyResponse;
import com.hospital.citas.dto.response.UserResponse;
import com.hospital.citas.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .role(user.getRole().getName().name())
                .isActive(user.getIsActive())
                .mustChangePassword(user.getMustChangePassword())
                .createdAt(user.getCreatedAt())
                .specialties(user.getSpecialties().stream()
                        .map(s -> SpecialtyResponse.builder().id(s.getId()).name(s.getName()).build())
                        .toList())
                .build();
    }
}
