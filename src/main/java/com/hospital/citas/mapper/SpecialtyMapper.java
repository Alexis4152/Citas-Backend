package com.hospital.citas.mapper;

import com.hospital.citas.dto.request.SpecialtyRequest;
import com.hospital.citas.dto.response.SpecialtyResponse;
import com.hospital.citas.entity.Specialty;
import org.springframework.stereotype.Component;

@Component
public class SpecialtyMapper {

    public void applyRequest(Specialty specialty, SpecialtyRequest req) {
        specialty.setName(req.getName());
        specialty.setDescription(req.getDescription());
        specialty.setRecommendations(req.getRecommendations());
    }

    public SpecialtyResponse toResponse(Specialty specialty) {
        return SpecialtyResponse.builder()
                .id(specialty.getId())
                .name(specialty.getName())
                .description(specialty.getDescription())
                .recommendations(specialty.getRecommendations())
                .build();
    }
}
