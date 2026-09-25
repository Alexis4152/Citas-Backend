package com.hospital.citas.service;

import com.hospital.citas.dto.request.ReceptionistCreateRequest;
import com.hospital.citas.dto.request.ReceptionistUpdateRequest;
import com.hospital.citas.dto.response.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReceptionistService {
    Page<UserResponse> search(String query, Pageable pageable);
    UserResponse create(ReceptionistCreateRequest request);
    UserResponse update(Long id, ReceptionistUpdateRequest request);
    /** Baja lógica: la cuenta deja de poder iniciar sesión y sale de listados activos. */
    void deactivate(Long id);
}
