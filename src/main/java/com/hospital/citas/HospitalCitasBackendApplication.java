package com.hospital.citas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class HospitalCitasBackendApplication {

    // Fija la zona horaria del JVM antes de que arranque el contexto de Spring, para que
    // LocalDateTime.now() (created_at de citas, calculo de horas de anticipacion, validacion
    // de horarios ya pasados/por vencer, etc.) refleje la hora local del hospital sin
    // importar en que zona horaria corra el contenedor (usualmente UTC). Default a Ciudad de
    // México, pero configurable via APP_TIMEZONE por si el hospital está en otra zona del
    // país (ej. "America/Tijuana", "America/Hermosillo") -- se lee con System.getenv (no
    // System.getProperty/@Value) porque corre ANTES de que exista el ApplicationContext de
    // Spring, en el momento en que se carga esta clase.
    static {
        String configuredTimeZone = System.getenv("APP_TIMEZONE");
        TimeZone.setDefault(TimeZone.getTimeZone(
                configuredTimeZone != null && !configuredTimeZone.isBlank() ? configuredTimeZone : "America/Mexico_City"));
    }

    public static void main(String[] args) {
        SpringApplication.run(HospitalCitasBackendApplication.class, args);
    }
}
