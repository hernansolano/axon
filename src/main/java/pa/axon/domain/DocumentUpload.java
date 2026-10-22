package pa.axon.domain;

import java.util.Optional;

/**
 * El archivo que la paciente adjunta al agregar un registro (HU-03). El tipo se lee de los primeros
 * bytes, no de la extensión ni de lo que diga el navegador, y solo pasan los tipos que /documento/{id}
 * sirve (DEC-21). El nombre original queda como dato y nunca se usa como ruta: en el disco el archivo
 * se llama por su UUID (RS-13, DEC-25).
 */
public record DocumentUpload(String originalName, String mimeType, byte[] content) {

    public static final int MAX_BYTES = 10 * 1024 * 1024;

    /** Lo que cabe en nombre_original. */
    static final int MAX_NAME = 200;

    /** Vacío si el contenido no es JPEG, PNG, WebP ni PDF. */
    public static Optional<DocumentUpload> of(String submittedName, byte[] content) {
        return mimeOf(content).map(mime -> new DocumentUpload(safeName(submittedName, mime), mime, content));
    }

    static Optional<String> mimeOf(byte[] c) {
        if (startsWith(c, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(c, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (startsWith(c, 0, 'R', 'I', 'F', 'F') && startsWith(c, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of("image/webp");
        }
        if (startsWith(c, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of("application/pdf");
        }
        return Optional.empty();
    }

    /**
     * Sin carpetas (hay navegadores que mandan la ruta completa), sin caracteres de control ni de
     * formato, y recortado a lo que cabe en la columna. Si no queda nada, un nombre genérico.
     */
    static String safeName(String submitted, String mime) {
        String name = submitted == null ? "" : submitted;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").strip();
        if (name.isEmpty()) {
            return "application/pdf".equals(mime) ? "documento.pdf" : "foto";
        }
        return name.codePointCount(0, name.length()) > MAX_NAME
                ? name.substring(0, name.offsetByCodePoints(0, MAX_NAME))
                : name;
    }

    private static boolean startsWith(byte[] content, int offset, int... expected) {
        if (content == null || content.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((content[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
