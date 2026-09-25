package com.hospital.citas.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "doctors")
@Getter @Setter @SuperBuilder @NoArgsConstructor
public class Doctor extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "specialty_id", nullable = false)
    private Specialty specialty;

    @Column(name = "license_number", length = 50)
    private String licenseNumber;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "default_slot_minutes", nullable = false)
    @Builder.Default
    private Integer defaultSlotMinutes = 30;

    /** Plantilla base que el doctor guarda para sus recetas -- se precarga como punto de
     * partida al escribir una receta nueva (ver Prescription), pero el texto de cada receta
     * se edita libremente y se guarda por separado, no ligado a esta plantilla. */
    @Column(name = "prescription_template", columnDefinition = "TEXT")
    private String prescriptionTemplate;

    /** Precio de la consulta que define el propio doctor (o el admin). Nulo = sin definir. */
    @Column(name = "consultation_price", precision = 10, scale = 2)
    private java.math.BigDecimal consultationPrice;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "doctor_branches",
            joinColumns = @JoinColumn(name = "doctor_id"),
            inverseJoinColumns = @JoinColumn(name = "branch_id")
    )
    @Builder.Default
    private List<Branch> branches = new ArrayList<>();
}
