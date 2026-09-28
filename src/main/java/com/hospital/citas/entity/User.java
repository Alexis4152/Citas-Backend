package com.hospital.citas.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Usuario del sistema (paciente con cuenta, recepcionista, doctor o administrador).
 * Implementa {@link UserDetails} directamente para integrarse con Spring Security: el
 * username es el email, y la autoridad otorgada se deriva del {@link Role} asignado. Un
 * usuario con borrado lógico ({@code isActive=false}) no puede autenticarse (ver
 * {@link #isEnabled()}).
 * <p>
 * Nota: los pacientes agendados por telefono/recepcion NUNCA generan una fila aqui — solo
 * viven como {@link Patient} sin {@code user} asociado. Toda fila de {@code User} siempre
 * tiene contraseña.
 */
@Entity
@Table(name = "users")
@Getter @Setter @SuperBuilder @NoArgsConstructor
public class User extends AuditableEntity implements UserDetails {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Único por hospital (el mismo correo puede tener cuenta en dos hospitales distintos).
    @Column(nullable = false, length = 150)
    private String email;

    @Column(name = "password_hash", nullable = false)
    @JsonIgnore
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 30)
    private String phone;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    /** Fuerza a pasar por /auth/change-password antes de usar el resto de la app (ver
     * patrón 02): true en el alta hecha por ADMIN con contraseña temporal, o tras un
     * reset por "olvidé mi contraseña". */
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private Boolean mustChangePassword = false;

    /** Intentos de login fallidos consecutivos; se resetea a 0 en cuanto uno tiene éxito.
     * Protección contra fuerza bruta (ver AuthServiceImpl#login). */
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    /** Si tiene un valor futuro, la cuenta está bloqueada temporalmente por demasiados
     * intentos fallidos -- ver {@link #isAccountNonLocked()}. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** Solo tiene sentido para RECEPTIONIST: si viene vacía, es un recepcionista general
     * (ve/agenda/administra citas de cualquier doctor, comportamiento de siempre). Si trae
     * una o más especialidades, queda restringido a doctores de esas especialidades (ver
     * AppointmentServiceImpl). EAGER a propósito: es un ManyToMany chico (0 filas para
     * PATIENT/DOCTOR/ADMIN, pocas para RECEPTIONIST) y así se evita manejar la carga LAZY en
     * los muchos puntos donde se mapea un User a UserResponse (login/registro/refresh/alta de
     * recepcionista), varios de los cuales no corren dentro de una transacción propia. */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "receptionist_specialties",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "specialty_id")
    )
    @Builder.Default
    private List<Specialty> specialties = new ArrayList<>();

    // ── UserDetails ──────────────────────────────────────────────

    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.getName()));
    }

    @Override @JsonIgnore public String getPassword()            { return passwordHash; }
    @Override public String getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isAccountNonLocked()      { return lockedUntil == null || lockedUntil.isBefore(LocalDateTime.now()); }
    @Override public boolean isEnabled()               { return Boolean.TRUE.equals(getIsActive()); }
}
