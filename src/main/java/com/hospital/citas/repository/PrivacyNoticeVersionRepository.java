package com.hospital.citas.repository;

import com.hospital.citas.entity.PrivacyNoticeVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PrivacyNoticeVersionRepository extends JpaRepository<PrivacyNoticeVersion, Long> {

    List<PrivacyNoticeVersion> findAllByOrderByVersionDesc();

    Optional<PrivacyNoticeVersion> findByVersion(Integer version);
}
