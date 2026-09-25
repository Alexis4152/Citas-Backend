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

    private ValidationPatterns() {
    }
}
