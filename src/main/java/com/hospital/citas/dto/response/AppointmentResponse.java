package com.hospital.citas.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AppointmentResponse {
    private Long id;
    private DoctorSummaryResponse doctor;
    private BranchResponse branch;
    private PatientResponse patient;
    private LocalDate appointmentDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String status;
    private String reasonForVisit;
    private String cancelReason;
    private LocalDateTime cancelledAt;
    /** Quién canceló: nombre completo, o "Paciente (invitado)" si se canceló vía el link
     * sin cuenta ({@code cancelledBy} nulo en ese caso). Null si la cita no está cancelada. */
    private String cancelledByName;
    /** Rol de quien canceló (PATIENT, DOCTOR, RECEPTIONIST, ADMIN) o "GUEST" para el caso
     * de invitado sin cuenta. Null si la cita no está cancelada. */
    private String cancelledByRole;
    private Boolean slotReleased;
    private Boolean releaseEligible;
    /** true solo cuando status=CANCELLED, slotReleased=false y releaseEligible=true. */
    private Boolean canRelease;
    private LocalDateTime releasedAt;
    /** Quién liberó el espacio (doctor o recepción/admin) -- control de auditoría. Null si
     * el espacio aún no se ha liberado. */
    private String releasedByName;
    private String releasedByRole;
    /** Cuándo se registró la llegada del paciente (null = aún no llega / no se registró). */
    private LocalDateTime arrivedAt;
    /** true si es un sobrecupo (empalmada a propósito sobre otra cita). */
    private Boolean overbooked;
    /** Precio de la consulta al agendar (nulo = el doctor no tenía precio definido). */
    private java.math.BigDecimal price;
    /** UNPAID, PENDING (SPEI sin pagar), PAID o REFUNDED. */
    private String paymentStatus;
    /** Solo se expone al crear la cita (para el link de cancelación sin login del invitado). */
    private String cancelToken;
}
