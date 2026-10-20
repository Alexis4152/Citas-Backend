package com.hospital.citas.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Un reporte ya calculado, listo para mostrarse en pantalla o bajarse en CSV, Excel o PDF:
 * título, periodo, un resumen de indicadores arriba, columnas tipadas, renglones y totales.
 * Los valores de cada renglón van en el mismo orden que {@link #columns}; su tipo decide cómo
 * se formatean (dinero, porcentaje, fecha...) en cada salida.
 */
public record ReportTable(
        String key,
        String title,
        String subtitle,
        List<Stat> summary,
        List<Column> columns,
        List<List<Object>> rows,
        List<Object> totals,
        String note) {

    public enum Type { TEXT, INT, MONEY, PERCENT, DATE, TIME, DATETIME }

    /** {@code weight}: ancho relativo de la columna en el PDF. */
    public record Column(String key, String label, Type type, float weight) {
        public boolean numeric() {
            return type == Type.INT || type == Type.MONEY || type == Type.PERCENT;
        }
    }

    /** Indicador del resumen (ya formateado como texto). */
    public record Stat(String label, String value) {
    }

    public static Builder builder(String key, String title) {
        return new Builder(key, title);
    }

    public static final class Builder {
        private final String key;
        private final String title;
        private String subtitle;
        private String note;
        private final List<Stat> summary = new ArrayList<>();
        private final List<Column> columns = new ArrayList<>();
        private final List<List<Object>> rows = new ArrayList<>();
        private List<Object> totals;

        private Builder(String key, String title) {
            this.key = key;
            this.title = title;
        }

        public Builder subtitle(String value) { this.subtitle = value; return this; }
        public Builder note(String value) { this.note = value; return this; }
        public Builder stat(String label, String value) { summary.add(new Stat(label, value)); return this; }

        public Builder col(String key, String label, Type type, float weight) {
            columns.add(new Column(key, label, type, weight));
            return this;
        }

        public Builder row(Object... values) {
            rows.add(java.util.Arrays.asList(values));
            return this;
        }

        public Builder totals(Object... values) {
            this.totals = java.util.Arrays.asList(values);
            return this;
        }

        public int rowCount() {
            return rows.size();
        }

        public ReportTable build() {
            return new ReportTable(key, title, subtitle, List.copyOf(summary), List.copyOf(columns), rows, totals, note);
        }
    }
}
