package com.hospital.citas.repository;

import com.hospital.citas.entity.Branch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    List<Branch> findByIsActiveTrue();
    Optional<Branch> findByIdAndIsActiveTrue(Long id);

    Page<Branch> findByNameContainingIgnoreCaseOrCityContainingIgnoreCase(String name, String city, Pageable pageable);
}
