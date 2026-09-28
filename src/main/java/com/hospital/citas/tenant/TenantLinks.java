package com.hospital.citas.tenant;

import com.hospital.citas.entity.Hospital;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Enlaces al frontend que viajan en correos, PDFs y QR. Cada hospital vive en
 * {@code <frontend>/c/<slug>}, así que todo enlace de un paciente/staff debe llevar el slug
 * del hospital de la operación en curso; sin hospital (SUPER_ADMIN), la raíz del sitio.
 */
@Component
@RequiredArgsConstructor
public class TenantLinks {

    private final HospitalDirectory hospitalDirectory;

    @Value("${app.frontend-url:http://localhost:5175}")
    private String frontendUrl;

    public String frontendBase() {
        String root = frontendUrl.replaceAll("/+$", "");
        Long hospitalId = TenantContext.hospitalIdOrNull();
        if (hospitalId == null) {
            return root;
        }
        Hospital hospital = hospitalDirectory.get(hospitalId);
        return hospital == null ? root : root + "/c/" + hospital.getSlug();
    }
}
