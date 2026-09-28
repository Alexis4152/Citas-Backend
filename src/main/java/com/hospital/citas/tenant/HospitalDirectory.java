package com.hospital.citas.tenant;

import com.hospital.citas.entity.Hospital;
import com.hospital.citas.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Datos de los hospitales que se consultan en CADA request (resolver el slug del link, saber
 * si está activo, armar enlaces, llaves de OpenPay), en caché. Cambian solo desde el panel del
 * SUPER_ADMIN, que llama a {@link #evict()} al guardar.
 */
@Component
@RequiredArgsConstructor
public class HospitalDirectory {

    private final HospitalRepository hospitalRepository;

    private final Map<String, Hospital> bySlug = new ConcurrentHashMap<>();
    private final Map<Long, Hospital> byId = new ConcurrentHashMap<>();

    /** Id del hospital ACTIVO con ese slug, o null. */
    public Long activeIdBySlug(String slug) {
        Hospital hospital = bySlug.computeIfAbsent(slug, s -> hospitalRepository.findBySlug(s).orElse(null));
        return hospital != null && Boolean.TRUE.equals(hospital.getIsActive()) ? hospital.getId() : null;
    }

    public boolean isActive(long hospitalId) {
        Hospital hospital = get(hospitalId);
        return hospital != null && Boolean.TRUE.equals(hospital.getIsActive());
    }

    public Hospital get(long hospitalId) {
        return byId.computeIfAbsent(hospitalId, id -> hospitalRepository.findById(id).orElse(null));
    }

    /** Hospital de la operación en curso (error si no hay uno). */
    public Hospital current() {
        Hospital hospital = get(TenantContext.requireHospitalId());
        if (hospital == null) {
            throw new MissingHospitalException();
        }
        return hospital;
    }

    public void evict() {
        bySlug.clear();
        byId.clear();
    }
}
