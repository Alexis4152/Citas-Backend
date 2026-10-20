package com.hospital.citas.repository;

import com.hospital.citas.entity.SlotHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlotHoldRepository extends JpaRepository<SlotHold, Long> {

    Optional<SlotHold> findByToken(UUID token);

    /** Apartados vigentes de un doctor entre dos fechas (para pintar la disponibilidad). */
    @Query("""
            SELECT h FROM SlotHold h
            WHERE h.doctorId = :doctorId AND h.holdDate BETWEEN :from AND :to AND h.expiresAt > :now
            """)
    List<SlotHold> findActive(@Param("doctorId") Long doctorId, @Param("from") LocalDate from,
                              @Param("to") LocalDate to, @Param("now") LocalDateTime now);

    /** Apartados vigentes que se traslapan con un rango de ese doctor y día. */
    @Query("""
            SELECT h FROM SlotHold h
            WHERE h.doctorId = :doctorId AND h.holdDate = :date
              AND h.startTime < :endTime AND h.endTime > :startTime AND h.expiresAt > :now
            """)
    List<SlotHold> findActiveOverlapping(@Param("doctorId") Long doctorId, @Param("date") LocalDate date,
                                         @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime,
                                         @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(h) FROM SlotHold h WHERE h.clientIp = :ip AND h.holderKey <> :holderKey AND h.expiresAt > :now")
    long countActiveByIpExcludingHolder(@Param("ip") String ip, @Param("holderKey") String holderKey,
                                        @Param("now") LocalDateTime now);

    List<SlotHold> findByHolderKey(String holderKey);

    @Query("SELECT DISTINCT h.doctorId FROM SlotHold h WHERE h.expiresAt <= :now")
    List<Long> findDoctorIdsWithExpired(@Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SlotHold h WHERE h.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    /** Quita un apartado vencido de ese mismo horario antes de crear uno nuevo (el índice único
     * no distingue vencidos de vigentes). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM SlotHold h
            WHERE h.doctorId = :doctorId AND h.holdDate = :date AND h.startTime = :startTime AND h.expiresAt <= :now
            """)
    int deleteExpiredAt(@Param("doctorId") Long doctorId, @Param("date") LocalDate date,
                        @Param("startTime") LocalTime startTime, @Param("now") LocalDateTime now);
}
