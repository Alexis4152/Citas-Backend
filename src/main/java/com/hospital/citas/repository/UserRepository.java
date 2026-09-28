package com.hospital.citas.repository;

import com.hospital.citas.entity.User;
import com.hospital.citas.enums.RoleName;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    /** Filtrada por el hospital actual ({@code @TenantId}): el correo es único por hospital. */
    Optional<User> findByEmail(String email);

    /** Usuarios de plataforma (SUPER_ADMIN), que no pertenecen a ningún hospital. Usar en ROOT. */
    Optional<User> findByEmailAndHospitalIdIsNull(String email);

    /** Para el SUPER_ADMIN (en ROOT): ¿ese hospital ya tiene una cuenta con este correo? */
    boolean existsByEmailAndHospitalId(String email, Long hospitalId);
    boolean existsByEmail(String email);
    List<User> findByRole_NameAndIsActiveTrue(RoleName roleName);

    /** Unicidad de teléfono entre cuentas de staff (ADMIN/DOCTOR/RECEPTIONIST) -- a
     * propósito NO incluye PATIENT: es normal que dos pacientes (ej. familiares) compartan
     * teléfono, pero dos miembros del staff no deberían, para no confundir a quién se
     * notifica/contacta. */
    boolean existsByPhoneAndIsActiveTrueAndRole_NameIn(String phone, Collection<RoleName> roles);
    boolean existsByPhoneAndIsActiveTrueAndRole_NameInAndIdNot(String phone, Collection<RoleName> roles, Long id);

    @Query("select u from User u where u.role.name = :role and u.isActive = true and (" +
            ":query is null or :query = '' " +
            "or lower(u.firstName) like lower(concat('%', :query, '%')) " +
            "or lower(u.lastName) like lower(concat('%', :query, '%')) " +
            "or lower(u.email) like lower(concat('%', :query, '%')))")
    Page<User> searchByRole(@Param("role") RoleName role, @Param("query") String query, Pageable pageable);
}
