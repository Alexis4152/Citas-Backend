package com.hospital.citas.repository;

import com.hospital.citas.entity.CashCut;
import com.hospital.citas.enums.CashCutStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CashCutRepository extends JpaRepository<CashCut, Long> {

    Optional<CashCut> findByUser_IdAndStatus(Long userId, CashCutStatus status);

    Page<CashCut> findByUser_Id(Long userId, Pageable pageable);
}
