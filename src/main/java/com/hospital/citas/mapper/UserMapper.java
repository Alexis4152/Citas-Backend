package com.hospital.citas.mapper;

import lombok.RequiredArgsConstructor;

import com.hospital.citas.tenant.HospitalDirectory;

import com.hospital.citas.entity.Hospital;

import com.hospital.citas.dto.response.SpecialtyResponse;
import com.hospital.citas.dto.response.UserResponse;
import com.hospital.citas.entity.User;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserMapper {

    private final HospitalDirectory hospitalDirectory;

    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .role(user.getRole().getName().name())
                .hospitalId(user.getHospitalId())
                .hospitalSlug(hospitalSlug(user.getHospitalId()))
                .isActive(user.getIsActive())
                .mustChangePassword(user.getMustChangePassword())
                .createdAt(user.getCreatedAt())
                .specialties(user.getSpecialties().stream()
                        .map(s -> SpecialtyResponse.builder().id(s.getId()).name(s.getName()).build())
                        .toList())
                .build();
    }

    private String hospitalSlug(Long hospitalId) {
        if (hospitalId == null) {
            return null;
        }
        Hospital hospital = hospitalDirectory.get(hospitalId);
        return hospital == null ? null : hospital.getSlug();
    }
}
