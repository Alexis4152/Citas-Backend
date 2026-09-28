package com.hospital.citas.tenant;

import com.hospital.citas.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Endpoints públicos que trabajan con el token de una cita (ver, cancelar, reprogramar, pagar,
 * comprobante). El token ya identifica la cita -- y con ella su hospital --, así que los
 * enlaces que se mandaron por correo antes de que existiera {@code /c/<slug>} siguen sirviendo.
 */
@Component
@RequiredArgsConstructor
public class TokenTenantResolver {

    private static final Pattern TOKEN_PATH = Pattern.compile(
            "^/api/(?:appointments/(?:by-token|cancel|reschedule|pay|guest-receipt)|public/appointment-hospital)/"
                    + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    private final AppointmentRepository appointmentRepository;

    /** Hospital de la cita del token en la ruta, o null si la ruta no es de ese tipo o no existe. */
    public Long hospitalIdFromPath(String path) {
        Matcher matcher = TOKEN_PATH.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        UUID token = UUID.fromString(matcher.group(1));
        List<Long> ids = TenantContext.callAs(TenantContext.ROOT, () -> appointmentRepository.findHospitalIdsByCancelToken(token));
        return ids.isEmpty() ? null : ids.get(0);
    }
}
