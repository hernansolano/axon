package pa.axon.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * H7: lo que se sube se acepta por su contenido, no por su nombre ni por lo que dice el navegador, y
 * solo si es de un tipo que /documento/{id} sirve (DEC-21). El nombre original queda limpio para
 * nombre_original y nunca es una ruta (DEC-25).
 */
class DocumentUploadTest {

    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P'};
    private static final byte[] PDF = "%PDF-1.7\n%sintetico".getBytes(StandardCharsets.US_ASCII);

    @Test
    void reconoceLosCuatroTiposQueElVisorSabeMostrar() {
        assertEquals("image/jpeg", DocumentUpload.of("a", JPG).orElseThrow().mimeType());
        assertEquals("image/png", DocumentUpload.of("a", PNG).orElseThrow().mimeType());
        assertEquals("image/webp", DocumentUpload.of("a", WEBP).orElseThrow().mimeType());
        assertEquals("application/pdf", DocumentUpload.of("a", PDF).orElseThrow().mimeType());
    }

    @Test
    void unHtmlOUnSvgConNombreDeFotoNoPasan() {
        // Servidos desde este origen correrían con la sesión de la paciente (DEC-21).
        assertTrue(DocumentUpload.of("receta.jpg", "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(DocumentUpload.of("receta.png", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(DocumentUpload.of("receta.pdf", new byte[] {'%', 'P', 'D'}).isEmpty());
        assertTrue(DocumentUpload.of("vacio.jpg", new byte[0]).isEmpty());
        assertTrue(DocumentUpload.of("nada.jpg", null).isEmpty());
    }

    @Test
    void elNombreSePasaSinCarpetasNiCaracteresDeControl() {
        assertEquals("receta.pdf", DocumentUpload.of("C:\\fakepath\\receta.pdf", PDF).orElseThrow().originalName());
        assertEquals("passwd", DocumentUpload.of("../../etc/passwd", PDF).orElseThrow().originalName());
        assertEquals("carnevacunas.jpg", DocumentUpload.of("carne\u0000\nvacunas.jpg", JPG).orElseThrow().originalName());
        assertEquals("gpj.exe", DocumentUpload.of("\u202Egpj.exe", JPG).orElseThrow().originalName());
    }

    @Test
    void sinNombreUsaUnoGenericoYUnNombreLargoSeRecortaALoQueCabeEnLaColumna() {
        assertEquals("foto", DocumentUpload.of(null, JPG).orElseThrow().originalName());
        assertEquals("documento.pdf", DocumentUpload.of("  /  ", PDF).orElseThrow().originalName());
        String largo = "á".repeat(250) + ".jpg";
        assertEquals("á".repeat(200), DocumentUpload.of(largo, JPG).orElseThrow().originalName());
    }
}
