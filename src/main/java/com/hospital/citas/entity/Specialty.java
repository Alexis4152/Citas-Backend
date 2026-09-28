package com.hospital.citas.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "specialties")
@Getter @Setter @SuperBuilder @NoArgsConstructor
public class Specialty extends AuditableEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100) // único por hospital
    private String name;

    @Column(length = 500)
    private String description;

    /** Recomendaciones para el paciente al agendar con un doctor de esta especialidad (ej.
     * "Acude con la vejiga llena" en Ginecología) -- reemplaza el texto genérico de
     * "llega 15 min antes..." en la confirmación, el correo y el comprobante en PDF cuando
     * viene capturado (ver AppointmentReceiptServiceImpl/EmailServiceImpl). Nulo/vacío usa
     * el texto genérico de respaldo. TEXT en vez de length=500 como description porque puede
     * ser más largo (varias indicaciones en un párrafo). */
    @Column(columnDefinition = "TEXT")
    private String recommendations;
}
