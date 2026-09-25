package com.hospital.citas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalTime;

/** Bloqueo puntual de disponibilidad (vacaciones, permiso, junta) que rompe el patrón semanal. */
@Entity
@Table(name = "doctor_schedule_exceptions")
@Getter @Setter @SuperBuilder @NoArgsConstructor
public class DoctorScheduleException extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "all_day", nullable = false)
    private Boolean allDay;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(columnDefinition = "TEXT")
    private String reason;
}
