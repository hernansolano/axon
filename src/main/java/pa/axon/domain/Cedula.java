package pa.axon.domain;

import java.util.Locale;

/**
 * Formato de la cédula panameña (DEC-24). Acepta todas las variantes vigentes, sin validar contra
 * el Registro Civil:
 * <ul>
 *   <li>provincia o comarca, 1 a 13: {@code 8-912-2043};</li>
 *   <li>con sufijo de registro anterior a la ley vigente o de población indígena: {@code 8AV-12-345},
 *       {@code 1PI-12-345};</li>
 *   <li>extranjero residente, naturalizado o panameño nacido en el extranjero: {@code E-8-123456},
 *       {@code N-19-1234}, {@code PE-8-1234}.</li>
 * </ul>
 * Tomo de 1 a 4 dígitos y asiento de 1 a 6. El patrón viene de github.com/merlos/cedula-panama (MIT).
 */
public final class Cedula {

    /** Compara contra la cédula ya normalizada: mayúsculas y sin espacios alrededor. */
    public static final String PATTERN = "(?:PE|E|N|(?:[1-9]|1[0-3])(?:AV|PI)?)-\\d{1,4}-\\d{1,6}";

    private Cedula() {
    }

    /** La misma cédula escrita {@code pe-8-1234} o {@code PE-8-1234} es una sola cuenta. */
    public static String normalizar(String cedula) {
        return cedula == null ? null : cedula.trim().toUpperCase(Locale.ROOT);
    }
}
