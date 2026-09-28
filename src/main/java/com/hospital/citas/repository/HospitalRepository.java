package com.hospital.citas.repository;

import com.hospital.citas.entity.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HospitalRepository extends JpaRepository<Hospital, Long> {

    Optional<Hospital> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Hospital> findByIsActiveTrueOrderByIdAsc();

    List<Hospital> findAllByOrderByNameAsc();
}
