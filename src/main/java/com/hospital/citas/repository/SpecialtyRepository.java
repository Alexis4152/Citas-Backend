package com.hospital.citas.repository;

import com.hospital.citas.entity.Specialty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecialtyRepository extends JpaRepository<Specialty, Long> {
    List<Specialty> findByIsActiveTrue();
    Optional<Specialty> findByIdAndIsActiveTrue(Long id);
    boolean existsByNameIgnoreCase(String name);

    Page<Specialty> findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(String name, String description, Pageable pageable);
}
