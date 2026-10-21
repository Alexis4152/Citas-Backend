package com.hospital.citas.repository;

import com.hospital.citas.entity.ArcoRequest;
import com.hospital.citas.enums.ArcoRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ArcoRequestRepository extends JpaRepository<ArcoRequest, Long> {

    /** Pendientes siempre primero (para que ninguna se pierda entre las resueltas); dentro de cada
     * grupo, las más antiguas primero entre pendientes (vencen antes) y las más recientes entre resueltas. */
    @Query("""
            SELECT a FROM ArcoRequest a
            LEFT JOIN FETCH a.resolvedBy
            WHERE (:status IS NULL OR a.status = :status)
            ORDER BY CASE WHEN a.status IN (com.hospital.citas.enums.ArcoRequestStatus.PENDING,
                                            com.hospital.citas.enums.ArcoRequestStatus.APPROVED,
                                            com.hospital.citas.enums.ArcoRequestStatus.INFO_REQUESTED) THEN 0 ELSE 1 END,
                     CASE WHEN a.status = com.hospital.citas.enums.ArcoRequestStatus.PENDING THEN a.dueDate END ASC,
                     a.createdAt DESC
            """)
    List<ArcoRequest> search(@Param("status") ArcoRequestStatus status);

    long countByStatus(ArcoRequestStatus status);

    java.util.Optional<ArcoRequest> findByTrackingTokenHash(String trackingTokenHash);

    List<ArcoRequest> findByRequesterUser_IdOrderByCreatedAtDesc(Long userId);

    /** Freno contra el abuso del formulario público: cuántas solicitudes trae ya ese correo hoy. */
    @Query("SELECT COUNT(a) FROM ArcoRequest a WHERE LOWER(a.requesterEmail) = LOWER(:email) AND a.createdAt >= :since")
    long countRecentByEmail(@Param("email") String email, @Param("since") LocalDateTime since);
}
