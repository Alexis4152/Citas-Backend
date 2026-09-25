package com.hospital.citas.repository;

import com.hospital.citas.entity.Appointment;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.enums.AppointmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, Long>, JpaSpecificationExecutor<Appointment> {

    Optional<Appointment> findByCancelToken(UUID cancelToken);

    /** Igual que {@link #findByCancelToken}, pero con doctor/sede/paciente ya cargados -- usada
     * por el comprobante público en PDF ({@code generateReceiptPdfByToken}), que no pasa por
     * {@code findAppointment}/{@link #findByIdWithDetails} porque busca por token, no por id. */
    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.branch
            JOIN FETCH a.patient
            WHERE a.cancelToken = :cancelToken
            """)
    Optional<Appointment> findByCancelTokenWithDetails(@Param("cancelToken") UUID cancelToken);

    /** Trae doctor+usuario+especialidad, sede y paciente en una sola consulta (en vez de que
     * cada uno se cargue LAZY por separado la primera vez que se toca) -- usada por
     * {@code findAppointment}, el punto de entrada que comparten booking/cancelación/
     * reprogramación/comprobante en PDF, todos los cuales terminan necesitando estos mismos
     * datos para construir la respuesta o el PDF. */
    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.branch
            JOIN FETCH a.patient
            WHERE a.id = :id
            """)
    Optional<Appointment> findByIdWithDetails(@Param("id") Long id);

    /**
     * Bloqueo pesimista sobre cualquier fila que ocupe (o se traslape con) el rango
     * [startTime, endTime) del doctor ese día, para serializar dos intentos de reserva
     * concurrentes contra el mismo horario (ver AppointmentServiceImpl, patrón calcado de
     * BookRepository.findByIdForUpdate / CheckoutServiceImpl en el proyecto de referencia).
     * Compara por TRASLAPE, no por hora de inicio idéntica: si el doctor cambió la duración
     * de sus espacios, una cita de 30 min a las 09:00 también ocupa el hueco de las 09:15.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.doctor.id = :doctorId AND a.appointmentDate = :date
              AND a.startTime < :endTime AND a.endTime > :startTime
              AND (a.status <> com.hospital.citas.enums.AppointmentStatus.CANCELLED OR a.slotReleased = false)
            """)
    List<Appointment> lockOccupantsForSlot(@Param("doctorId") Long doctorId,
                                            @Param("date") LocalDate date,
                                            @Param("startTime") LocalTime startTime,
                                            @Param("endTime") LocalTime endTime);

    @Query("""
            SELECT a FROM Appointment a
            WHERE a.doctor.id = :doctorId AND a.appointmentDate BETWEEN :from AND :to
              AND (a.status <> com.hospital.citas.enums.AppointmentStatus.CANCELLED OR a.slotReleased = false)
            """)
    List<Appointment> findOccupiedSlots(@Param("doctorId") Long doctorId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    List<Appointment> findByPatient_IdOrderByAppointmentDateDescStartTimeDesc(Long patientId);

    /** Citas programadas todavía vigentes de un guest identificado por teléfono + nombre +
     * apellido -- ya NO se devuelven al navegador (ver AppointmentServiceImpl
     * .requestGuestAppointmentLinks): solo sirven para mandar los enlaces al correo registrado.
     * Excluye a los pacientes con cuenta ({@code patient.user IS NOT NULL}): esos ya tienen
     * "Mis citas" con login. */
    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.branch
            JOIN FETCH a.patient p
            WHERE p.phone = :phone AND LOWER(p.firstName) = LOWER(:firstName) AND LOWER(p.lastName) = LOWER(:lastName)
              AND p.user IS NULL
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            ORDER BY a.appointmentDate, a.startTime
            """)
    List<Appointment> findUpcomingGuestAppointments(@Param("phone") String phone, @Param("firstName") String firstName,
                                                    @Param("lastName") String lastName, @Param("today") LocalDate today,
                                                    @Param("now") LocalTime now);

    List<Appointment> findByDoctor_IdAndAppointmentDateBetweenOrderByAppointmentDateAscStartTimeAsc(
            Long doctorId, LocalDate from, LocalDate to);

    long countByAppointmentDateAndStatus(LocalDate date, AppointmentStatus status);

    long countByAppointmentDateBetweenAndStatus(LocalDate from, LocalDate to, AppointmentStatus status);

    /** Programadas cuyo horario (más el margen de gracia que calcula el llamador) ya terminó
     * -- usada por la tarea automática que las marca "no asistió" cuando nadie intervino a
     * tiempo (ver AppointmentServiceImpl.autoMarkPastDueAsNoShow). Las que ya tienen la
     * llegada del paciente registrada nunca entran: el paciente sí está (o estuvo) ahí. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND a.arrivedAt IS NULL
              AND (a.appointmentDate < :cutoffDate OR (a.appointmentDate = :cutoffDate AND a.endTime <= :cutoffTime))
            """)
    List<Appointment> findPastDueScheduled(@Param("cutoffDate") LocalDate cutoffDate, @Param("cutoffTime") LocalTime cutoffTime);

    /** Citas de días anteriores con llegada registrada que nadie cerró: el paciente sí
     * estuvo, así que se cierran como atendidas en vez de quedar "programadas" para siempre. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND a.arrivedAt IS NOT NULL AND a.appointmentDate < :today
            """)
    List<Appointment> findStaleArrived(@Param("today") LocalDate today);

    /** Programadas que empiezan dentro de las próximas 2 horas (entre "ahora" y "ahora + 2h")
     * y a las que aún no se les mandó el recordatorio -- ver AppointmentServiceImpl
     * .sendTwoHourReminders. Trae doctor+usuario+especialidad, sede y paciente ya cargados
     * (igual que {@link #findByIdWithDetails}) porque el correo se manda de forma async en
     * OTRO hilo, después de que esta consulta y su transacción ya terminaron -- sin el JOIN
     * FETCH, tocar esas relaciones desde el hilo de notificaciones lanzaría
     * LazyInitializationException. */
    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.doctor d
            JOIN FETCH d.user
            JOIN FETCH d.specialty
            JOIN FETCH a.branch
            JOIN FETCH a.patient
            WHERE a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND a.reminder2hSentAt IS NULL
              AND (a.appointmentDate > :nowDate OR (a.appointmentDate = :nowDate AND a.startTime > :nowTime))
              AND (a.appointmentDate < :thresholdDate OR (a.appointmentDate = :thresholdDate AND a.startTime <= :thresholdTime))
            """)
    List<Appointment> findDueForTwoHourReminder(@Param("nowDate") LocalDate nowDate, @Param("nowTime") LocalTime nowTime,
                                                  @Param("thresholdDate") LocalDate thresholdDate, @Param("thresholdTime") LocalTime thresholdTime);

    // ── Consultas para reglas de negocio ────────────────────────────

    /** Citas programadas del paciente que se traslapan con [start, end) ese día -- evita que
     * la misma persona quede agendada con dos doctores a la misma hora. */
    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.patient.id = :patientId AND a.appointmentDate = :date
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND a.startTime < :endTime AND a.endTime > :startTime
              AND a.id <> :excludeId
            """)
    long countPatientOverlaps(@Param("patientId") Long patientId, @Param("date") LocalDate date,
                              @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime,
                              @Param("excludeId") Long excludeId);

    /** Citas programadas del paciente que todavía no terminan. */
    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.patient.id = :patientId
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            """)
    long countUpcomingByPatient(@Param("patientId") Long patientId, @Param("today") LocalDate today,
                                @Param("now") LocalTime now);

    /** Igual que {@link #countUpcomingByPatient}, pero por teléfono (todas las personas que
     * comparten ese número) -- freno contra quien aparta muchos horarios como invitado. */
    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.patient.phone = :phone
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            """)
    long countUpcomingByPhone(@Param("phone") String phone, @Param("today") LocalDate today,
                              @Param("now") LocalTime now);

    /** Citas programadas que todavía no terminan de un doctor -- base para validar que un
     * cambio de agenda no deje citas huérfanas. Trae la sede para compararla con los horarios. */
    @Query("""
            SELECT a FROM Appointment a
            JOIN FETCH a.branch
            WHERE a.doctor.id = :doctorId
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            """)
    List<Appointment> findUpcomingScheduledByDoctor(@Param("doctorId") Long doctorId, @Param("today") LocalDate today,
                                                    @Param("now") LocalTime now);

    /** Citas programadas de un doctor ese día que se traslapan con [from, to) -- las que un
     * bloqueo de agenda (vacaciones, junta) dejaría afectadas. */
    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.doctor.id = :doctorId AND a.appointmentDate = :date
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND a.startTime < :to AND a.endTime > :from
            """)
    long countScheduledInWindow(@Param("doctorId") Long doctorId, @Param("date") LocalDate date,
                                @Param("from") LocalTime from, @Param("to") LocalTime to);

    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.doctor.id = :doctorId
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            """)
    long countUpcomingByDoctor(@Param("doctorId") Long doctorId, @Param("today") LocalDate today, @Param("now") LocalTime now);

    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.doctor.id = :doctorId AND a.branch.id = :branchId
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            """)
    long countUpcomingByDoctorAndBranch(@Param("doctorId") Long doctorId, @Param("branchId") Long branchId,
                                        @Param("today") LocalDate today, @Param("now") LocalTime now);

    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.branch.id = :branchId
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            """)
    long countUpcomingByBranch(@Param("branchId") Long branchId, @Param("today") LocalDate today, @Param("now") LocalTime now);

    /** Citas programadas vigentes de una sede que caerían fuera de un horario de apertura nuevo. */
    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.branch.id = :branchId
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
              AND (a.startTime < :open OR a.endTime > :close)
            """)
    long countUpcomingOutsideHours(@Param("branchId") Long branchId, @Param("today") LocalDate today,
                                   @Param("now") LocalTime now, @Param("open") LocalTime open, @Param("close") LocalTime close);

    @Query("""
            SELECT COUNT(a) FROM Appointment a
            WHERE a.doctor.specialty.id = :specialtyId
              AND a.status = com.hospital.citas.enums.AppointmentStatus.SCHEDULED
              AND (a.appointmentDate > :today OR (a.appointmentDate = :today AND a.endTime > :now))
            """)
    long countUpcomingBySpecialty(@Param("specialtyId") Long specialtyId, @Param("today") LocalDate today, @Param("now") LocalTime now);

    /** Fusión de pacientes duplicados: todas las citas del paciente origen pasan al destino. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Appointment a SET a.patient = :target, a.version = a.version + 1 WHERE a.patient = :source")
    int reassignPatient(@Param("source") Patient source, @Param("target") Patient target);
}
