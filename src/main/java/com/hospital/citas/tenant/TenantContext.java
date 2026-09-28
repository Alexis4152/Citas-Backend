package com.hospital.citas.tenant;

import java.util.function.Supplier;

/**
 * Hospital (tenant) de la operación en curso, por hilo. Lo fija {@code JwtAuthFilter} al
 * inicio de cada request (el del usuario autenticado, o el del link {@code /c/<slug>} que
 * manda el frontend en el header {@code X-Hospital}) y lo leen Hibernate
 * ({@link TenantIdentifierResolver}, que filtra TODAS las consultas por {@code hospital_id})
 * y {@link TenantGuard}.
 * <ul>
 *   <li>{@link #ROOT}: sin filtro -- super admin, tareas programadas que recorren hospitales,
 *       y búsquedas internas por token (refresh, reset de contraseña, webhook).</li>
 *   <li>{@link #NONE}: no se sabe el hospital (request público sin link): no ve ningún dato.</li>
 * </ul>
 */
public final class TenantContext {

    public static final long ROOT = 0L;
    public static final long NONE = -1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Hospital actual, {@link #ROOT} o {@link #NONE} (nunca null). */
    public static long get() {
        Long value = CURRENT.get();
        return value == null ? NONE : value;
    }

    public static void set(long tenant) {
        CURRENT.set(tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean isRoot() {
        return get() == ROOT;
    }

    /** Id del hospital actual, o null si es ROOT/NONE. */
    public static Long hospitalIdOrNull() {
        long value = get();
        return value > 0 ? value : null;
    }

    /** Id del hospital actual; si no hay uno, error de negocio (400). */
    public static long requireHospitalId() {
        Long id = hospitalIdOrNull();
        if (id == null) {
            throw new MissingHospitalException();
        }
        return id;
    }

    /**
     * Ejecuta {@code work} con otro tenant y restaura el anterior. Ojo: Hibernate fija el
     * tenant al ABRIR la sesión, así que esto solo surte efecto para transacciones que
     * empiecen dentro de {@code work} (no cambia el de una transacción ya abierta).
     */
    public static <T> T callAs(long tenant, Supplier<T> work) {
        Long previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(long tenant, Runnable work) {
        callAs(tenant, () -> {
            work.run();
            return null;
        });
    }
}
