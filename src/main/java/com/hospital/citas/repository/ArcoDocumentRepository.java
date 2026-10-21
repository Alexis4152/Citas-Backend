package com.hospital.citas.repository;

import com.hospital.citas.entity.ArcoDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ArcoDocumentRepository extends JpaRepository<ArcoDocument, Long> {

    @Query("SELECT d FROM ArcoDocument d WHERE d.arcoRequest.id = :id AND d.deletedAt IS NULL ORDER BY d.uploadedAt ASC")
    List<ArcoDocument> listActive(@Param("id") Long arcoRequestId);

    Optional<ArcoDocument> findFirstByArcoRequest_IdAndKindAndDeletedAtIsNullOrderByUploadedAtDesc(Long arcoRequestId, String kind);

    /** Identificaciones de solicitudes ya cerradas hace tiempo: se borran (minimización). */
    @Query("""
            SELECT d FROM ArcoDocument d
            WHERE d.kind IN ('IDENTITY', 'INFO') AND d.deletedAt IS NULL
              AND d.arcoRequest.status IN (com.hospital.citas.enums.ArcoRequestStatus.COMPLETED,
                                           com.hospital.citas.enums.ArcoRequestStatus.REJECTED,
                                           com.hospital.citas.enums.ArcoRequestStatus.NOT_FILED)
              AND d.arcoRequest.resolvedAt < :before
            """)
    List<ArcoDocument> findIdentityDocsClosedBefore(@Param("before") LocalDateTime before);
}
