package com.hospital.citas.service;

import com.hospital.citas.dto.request.SpecialtyRequest;
import com.hospital.citas.dto.response.SpecialtyResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface SpecialtyService {
    List<SpecialtyResponse> listActive();
    Page<SpecialtyResponse> adminSearch(String query, Pageable pageable);
    SpecialtyResponse create(SpecialtyRequest request);
    SpecialtyResponse update(Long id, SpecialtyRequest request);
    void deactivate(Long id);
}
