package pa.axon.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/** Las tres vigencias de la pantalla de compartir (UI.md 09). No hay permiso sin vencimiento (RS-06). */
public enum ShareDuration {
    MIN_15(15, "15 min", "15 minutos"),
    HORAS_2(120, "2 horas", "2 horas"),
    HORAS_24(1440, "24 horas", "24 horas");

    public static final ShareDuration DEFAULT = HORAS_2;

    private final int minutes;
    private final String label;
    private final String longLabel;

    ShareDuration(int minutes, String label, String longLabel) {
        this.minutes = minutes;
        this.label = label;
        this.longLabel = longLabel;
    }

    /** Solo valen las tres de la lista: cualquier otro número que llegue en la solicitud es vacío. */
    public static Optional<ShareDuration> ofMinutes(String minutes) {
        return Arrays.stream(values()).filter(d -> String.valueOf(d.minutes).equals(minutes)).findFirst();
    }

    public int getMinutes() {
        return minutes;
    }

    public Duration toDuration() {
        return Duration.ofMinutes(minutes);
    }

    /** En el botón: «15 min». */
    public String getLabel() {
        return label;
    }

    /** En el resumen: «Vence solo en 15 minutos». */
    public String getLongLabel() {
        return longLabel;
    }
}
