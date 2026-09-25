package com.hospital.citas.service;

import com.hospital.citas.dto.request.DoctorCreateRequest;
import com.hospital.citas.dto.request.DoctorProfileUpdateRequest;
import com.hospital.citas.dto.request.DoctorUpdateRequest;
import com.hospital.citas.dto.response.DoctorDetailResponse;
import com.hospital.citas.dto.response.DoctorSummaryResponse;
import com.hospital.citas.entity.Doctor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface DoctorService {

    Page<DoctorSummaryResponse> search(Long specialtyId, Long branchId, Pageable pageable);

    DoctorDetailResponse getDetail(Long id);

    DoctorDetailResponse adminCreate(DoctorCreateRequest request);

    /** Edita perfil + sedes de un doctor ya existente (ADMIN). */
    DoctorDetailResponse adminUpdate(Long id, DoctorUpdateRequest request);

    /** Baja lógica: desactiva el perfil Doctor Y su cuenta User asociada (ya no puede
     * iniciar sesión ni aparecer en listados/selectores activos). */
    void deactivate(Long id);

    /** Sube y reemplaza la foto del doctor (patrón 08/09: valida tamaño/extensión antes de
     * escribir vía {@link com.hospital.citas.service.FileStorageService}). */
    DoctorDetailResponse updatePhoto(Long doctorId, MultipartFile photo);

    DoctorDetailResponse getOwnProfile();

    DoctorDetailResponse updateOwnPrice(java.math.BigDecimal price);

    DoctorDetailResponse updateOwnProfile(DoctorProfileUpdateRequest request);

    /** Entidad Doctor asociada al usuario DOCTOR actualmente autenticado. */
    Doctor getOwnDoctorEntity();
}
