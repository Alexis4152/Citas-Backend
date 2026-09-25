package com.hospital.citas.repository;

import com.hospital.citas.entity.Doctor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long>, JpaSpecificationExecutor<Doctor> {

    @EntityGraph(attributePaths = {"user", "specialty", "branches"})
    Optional<Doctor> findByIdAndIsActiveTrue(Long id);

    @EntityGraph(attributePaths = {"user", "specialty", "branches"})
    Page<Doctor> findAll(org.springframework.data.jpa.domain.Specification<Doctor> spec, Pageable pageable);

    Optional<Doctor> findByUser_Id(Long userId);

    Optional<Doctor> findByUser_Email(String email);

    boolean existsByUser_Id(Long userId);

    long countByIsActiveTrue();
}
