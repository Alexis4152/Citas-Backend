package com.hospital.citas.repository;

import com.hospital.citas.entity.AuditLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    /**
     * Filtro de la bitácora. Solo agrega las condiciones que sí vienen: con parámetros nulos en
     * la consulta ({@code :x IS NULL OR ...}) Postgres no sabe su tipo y falla con lower(bytea).
     */
    static Specification<AuditLog> filter(String action, String entityType, Long entityId, String userQuery,
                                          LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (action != null) p.add(cb.equal(root.get("action"), action));
            if (entityType != null) p.add(cb.equal(root.get("entityType"), entityType));
            if (entityId != null) p.add(cb.equal(root.get("entityId"), entityId));
            if (userQuery != null) {
                p.add(cb.like(cb.lower(root.get("userName")), "%" + userQuery.toLowerCase(Locale.ROOT) + "%"));
            }
            p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            p.add(cb.lessThan(root.get("createdAt"), to));
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
