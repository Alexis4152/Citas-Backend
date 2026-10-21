package com.hospital.citas.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/** Día inhábil oficial (no cuenta en los plazos de días hábiles). Común a todos los hospitales. */
@Entity
@Table(name = "non_business_days")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class NonBusinessDay {

    @Id
    @Column(name = "holiday_date")
    private LocalDate day;

    @Column(nullable = false, length = 120)
    private String description;
}
