package com.hospital.citas.service;

import com.hospital.citas.dto.request.*;
import com.hospital.citas.dto.response.AppointmentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public interface AppointmentService {

    AppointmentResponse bookGuest(GuestAppointmentRequest request);

    AppointmentResponse bookSelf(AppointmentRequest request);

    AppointmentResponse bookForPatient(ReceptionAppointmentRequest request);

    /** Público: detalle de la cita para quien tiene su token (mismo modelo de confianza que el
     * comprobante en PDF por token) -- así la página de cancelar/reprogramar muestra QUÉ cita
     * es antes de actuar, en vez de cancelar a ciegas. */
    AppointmentResponse getByToken(UUID token);

    AppointmentResponse cancelByToken(UUID token, CancelAppointmentRequest request);

    AppointmentResponse cancelOwn(Long appointmentId, CancelAppointmentRequest request);

    AppointmentResponse cancelByStaff(Long appointmentId, CancelAppointmentRequest request);

    AppointmentResponse releaseSlot(Long appointmentId);

    AppointmentResponse reschedule(Long appointmentId, RescheduleRequest request);

    /** El propio paciente reprograma su cita (sin llamar al hospital), con el mismo doctor y
     * al menos N horas de anticipación (ver app.appointments.self-reschedule-min-hours). */
    AppointmentResponse rescheduleOwn(Long appointmentId, RescheduleRequest request);

    /** Igual que {@link #rescheduleOwn}, pero para el invitado, con el token de su cita. */
    AppointmentResponse rescheduleByToken(UUID token, RescheduleRequest request);

    /** Recepción o doctor (solo sus citas): el paciente ya está en el hospital. Una cita con
     * llegada registrada nunca se marca automáticamente como "no asistió". */
    AppointmentResponse markArrived(Long appointmentId);

    /** Recepción, doctor (solo sus propias citas) o Admin -- solo tiene sentido una vez que
     * la cita ya ocurrió (ver validación en el impl). */
    AppointmentResponse markCompleted(Long appointmentId);

    /** Recepción, doctor (solo sus propias citas) o Admin -- igual que markCompleted, solo
     * una vez que la cita ya ocurrió. */
    AppointmentResponse markNoShow(Long appointmentId);

    List<AppointmentResponse> listOwn();

    /** Vista paginada/filtrable de "Mis citas" del paciente autenticado (fecha, especialidad,
     * doctor) -- mismo patrón que {@link #searchOwnDoctor} pero con patientId fijo al propio
     * en vez de doctorId. */
    Page<AppointmentResponse> searchOwn(Long doctorId, Long specialtyId, LocalDate from, LocalDate to,
                                         String status, Pageable pageable);

    /** Público, sin login: para el invitado que perdió su código de cancelación. Sin fecha/hora,
     * por privacidad NO devuelve citas ni tokens (teléfono + nombre son fáciles de conocer para un
     * tercero): manda los enlaces al correo que dejó al agendar y responde siempre igual. Con la
     * fecha y hora exactas de la cita (vía para quien no dejó correo) devuelve esa cita, sin
     * motivo de consulta ni datos médicos; si no coincide, lanza 404. Devuelve null en el modo correo. */
    AppointmentResponse requestGuestAppointmentLinks(String phone, String firstName, String lastName,
                                                      LocalDate appointmentDate, LocalTime startTime);

    Page<AppointmentResponse> search(Long doctorId, Long patientId, Long branchId, LocalDate from, LocalDate to,
                                      String status, String patientQuery, boolean pendingRelease,
                                      boolean pendingPayment, Pageable pageable);

    List<AppointmentResponse> listForOwnDoctor(LocalDate from, LocalDate to);

    /** Vista paginada/filtrable de "Mis citas" del doctor autenticado -- a diferencia de
     * {@link #listForOwnDoctor}, que trae todo el rango para armar el calendario semanal de
     * "Mi agenda", esta es para la lista con filtros (fecha, estado, paciente) + paginación,
     * mismo patrón que {@link #search} de recepción pero con doctorId fijo al propio. */
    Page<AppointmentResponse> searchOwnDoctor(Long branchId, LocalDate from, LocalDate to, String status,
                                               String patientQuery, Pageable pageable);

    /**
     * Módulo de cobro de recepción: encuentra citas por lo que escanee o escriba -- el QR del
     * comprobante (su URL o su código), el folio (#123) o el nombre/teléfono del paciente. Sin
     * texto devuelve la cola de cobro (atendidas sin pagar + programadas de hoy sin pagar).
     */
    List<AppointmentResponse> lookupForCharge(String q);

    // ── Comprobante en PDF (patrones 09/11) ─────────────────────────
    // Cada método reutiliza la MISMA verificación de dueño que ya aplican los demás métodos
    // de este servicio para el rol correspondiente (ver AppointmentServiceImpl) -- no hay un
    // endpoint "genérico" que reimplemente permisos.

    /** Paciente autenticado, dueño de la cita. */
    byte[] generateReceiptPdfOwn(Long appointmentId);

    /** Público, vía el mismo token de cancelación (mismo modelo de confianza que
     * {@code /appointments/cancel/{token}}). */
    byte[] generateReceiptPdfByToken(UUID token);

    /** Recepción/Admin: cualquier cita. */
    byte[] generateReceiptPdfForStaff(Long appointmentId);

    /** Doctor: solo sus propias citas (Admin sin restricción). */
    byte[] generateReceiptPdfForDoctor(Long appointmentId);
}
