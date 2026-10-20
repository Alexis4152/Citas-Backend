package com.hospital.citas.security;

import com.hospital.citas.enums.RoleName;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catálogo de permisos (patrón "Roles y permisos" de nexora-hotel, ajustado a citas médicas).
 * El ADMIN del hospital siempre tiene todos. Para Recepción y Doctor, cada hospital puede
 * ajustar qué puede hacer cada rol, y además dar o quitar permisos a una persona en particular.
 * Lo que no esté personalizado usa estos valores por defecto (los de siempre del sistema).
 */
public enum Permission {

    APPOINTMENTS_CREATE("Citas", "Agendar citas", true, false),
    APPOINTMENTS_RESCHEDULE("Citas", "Reprogramar citas", true, true),
    APPOINTMENTS_CANCEL("Citas", "Cancelar citas", true, true),
    APPOINTMENTS_OVERBOOK("Citas", "Agendar sobrecupos", true, false),
    APPOINTMENTS_RELEASE("Citas", "Liberar horarios de citas canceladas", true, true),
    APPOINTMENTS_STATUS("Citas", "Registrar llegada, atendida y no asistió", true, true),
    /** Solo muestra u oculta el menú: la disponibilidad de los doctores también la usa "Nueva cita". */
    AGENDA_DOCTORS("Agenda", "Ver la agenda de los doctores", true, false),

    PATIENTS_MODULE("Pacientes", "Abrir expedientes de pacientes", false, true),
    PATIENTS_EDIT("Pacientes", "Editar datos personales e información médica", true, true),
    PATIENTS_MERGE("Pacientes", "Fusionar pacientes duplicados", false, false),
    PATIENTS_EXPORT("Pacientes", "Exportar la lista de pacientes", false, false),
    PATIENTS_IMPORT("Pacientes", "Importar pacientes desde Excel", false, false),

    PRESCRIPTIONS_VIEW("Recetas", "Ver y descargar recetas", false, true),

    PAYMENTS_CHARGE("Caja", "Cobrar consultas", true, true),
    CASH_CUT("Caja", "Abrir y cerrar su corte de caja", true, true),

    REPORTS_VIEW("Reportes", "Ver reportes de operación", false, true),
    REPORTS_FINANCIAL("Reportes", "Ver ingresos en los reportes", false, true),
    REPORTS_EXPORT("Reportes", "Exportar reportes a Excel", false, false);

    private final String module;
    private final String label;
    private final boolean receptionistDefault;
    private final boolean doctorDefault;

    Permission(String module, String label, boolean receptionistDefault, boolean doctorDefault) {
        this.module = module;
        this.label = label;
        this.receptionistDefault = receptionistDefault;
        this.doctorDefault = doctorDefault;
    }

    public String module() {
        return module;
    }

    public String label() {
        return label;
    }

    public boolean defaultFor(RoleName role) {
        return switch (role) {
            case RECEPTIONIST -> receptionistDefault;
            case DOCTOR -> doctorDefault;
            case ADMIN -> true;
            default -> false;
        };
    }

    public static Set<Permission> defaultsFor(RoleName role) {
        return Arrays.stream(values()).filter(p -> p.defaultFor(role))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }

    /** Roles cuyos permisos se pueden personalizar (el ADMIN siempre tiene todo). */
    public static boolean configurable(RoleName role) {
        return role == RoleName.RECEPTIONIST || role == RoleName.DOCTOR;
    }
}
