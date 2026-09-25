package com.hospital.citas.repository;

import com.hospital.citas.entity.Role;
import com.hospital.citas.enums.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(RoleName name);
}
