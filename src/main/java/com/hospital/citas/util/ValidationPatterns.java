package com.hospital.citas.util;

/**
 * Expresiones regulares reusadas por varios DTOs de request, para no duplicar el mismo
 * patrón (y su mensaje de error) en cada uno.
 */
public final class ValidationPatterns {

    /** Teléfono a 10 dígitos numéricos, obligatorio (se combina con @NotBlank). */
    public static final String PHONE_REGEXP = "^\\d{10}$";

    /** Igual que {@link #PHONE_REGEXP} pero permite cadena vacía, para los campos de
     * teléfono que hoy son opcionales -- no se vuelven obligatorios, solo se valida el
     * formato cuando sí se captura algo. */
    public static final String PHONE_REGEXP_OPTIONAL = "^$|^\\d{10}$";

    public static final String PHONE_MESSAGE = "El teléfono debe tener exactamente 10 dígitos numéricos";

    /** Al menos una mayúscula y un número, además del mínimo de longitud que ya exige @Size. */
    public static final String PASSWORD_REGEXP = "^(?=.*[A-Z])(?=.*\\d).+$";

    public static final String PASSWORD_MESSAGE = "La contraseña debe incluir al menos una mayúscula y un número";

    /** BCrypt solo usa los primeros 72 bytes: una contraseña más larga se truncaría en silencio. */
    public static final int PASSWORD_MAX = 72;

    public static final String PASSWORD_MAX_MESSAGE = "La contraseña no puede superar 72 caracteres";

    /**
     * Nombre o apellido de una persona real (patrón de nexora-hotel): letras de cualquier
     * alfabeto con acentos, ñ y diéresis, y espacios, apóstrofos, guiones o puntos entre
     * palabras. Sin dígitos ni símbolos: así tampoco se cuela HTML en correos y PDFs.
     * Espejado en el frontend (src/utils/validators.js).
     */
    public static final String NAME_REGEXP = "^\\s*[\\p{L}\\p{M}]+(?:[ '’.\\-]{1,2}[\\p{L}\\p{M}]+)*\\.?\\s*$";

    /** Igual que {@link #NAME_REGEXP}; para campos de nombre completo (nombre y apellidos juntos). */
    public static final String FULL_NAME_REGEXP = NAME_REGEXP;

    public static final String NAME_MESSAGE = "El nombre solo puede tener letras, espacios, apóstrofos, guiones y puntos";

    /** {@code @Email} acepta "ana@localhost"; un correo real necesita dominio con punto. */
    public static final String EMAIL_REGEXP = "^[^\\s@<>()\\[\\],;:\"]+@[^\\s@<>()\\[\\],;:\"]+\\.[A-Za-z]{2,}$";

    /** Color de marca: siempre #RRGGBB, nunca CSS libre (se inserta en estilos y correos). */
    public static final String HEX_COLOR_REGEXP = "^$|^#[0-9A-Fa-f]{6}$";

    /** Código postal mexicano: 5 dígitos (vacío = no capturado). */
    public static final String POSTAL_CODE_REGEXP = "^$|^\\d{5}$";

    /** RFC del SAT: persona moral (12) o física (13). */
    public static final String RFC_REGEXP = "^$|^[A-ZÑ&]{3,4}\\d{6}[A-Z\\d]{3}$";

    /** CURP: 18 caracteres con la estructura oficial. */
    public static final String CURP_REGEXP = "^$|^[A-Z][AEIOUX][A-Z]{2}\\d{6}[HMX][A-Z]{5}[A-Z\\d]\\d$";

    /** Sexo registrado: F, M o X (no binario / prefiere no decir). */
    public static final String SEX_REGEXP = "^$|^[FMX]$";

    /** Sitio web: debe empezar con http:// o https://. */
    public static final String WEBSITE_REGEXP = "^$|^https?://[^\\s]{3,200}$";

    private ValidationPatterns() {
    }
}
