package com.hospital.citas.service;

import com.hospital.citas.entity.Doctor;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class DoctorSpecifications {

    private DoctorSpecifications() {
    }

    public static Specification<Doctor> search(Long specialtyId, Long branchId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("isActive")));
            if (specialtyId != null) {
                predicates.add(cb.equal(root.get("specialty").get("id"), specialtyId));
            }
            if (branchId != null) {
                if (query != null) {
                    query.distinct(true);
                }
                predicates.add(cb.equal(root.join("branches").get("id"), branchId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
