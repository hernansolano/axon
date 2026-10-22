package pa.axon.web;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import pa.axon.domain.CartillaService;
import pa.axon.domain.CartillaService.ArchivoVista;
import pa.axon.security.AxonUser;

/**
 * Única salida de los archivos de la cartilla (RS-13). El id es el UUID de ruta_almacen. Si no
 * existe, si es de otra paciente o si el archivo ya no está en el disco, la respuesta es la misma:
 * 404 (CP-15).
 */
@Controller
public class DocumentController {

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final Set<String> SERVIBLES = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");

    private final CartillaService cartilla;

    public DocumentController(CartillaService cartilla) {
        this.cartilla = cartilla;
    }

    @GetMapping("/documento/{id:" + UUID_PATTERN + "}")
    public ResponseEntity<byte[]> documento(@AuthenticationPrincipal AxonUser user, @PathVariable UUID id) {
        // Solo tipos que el navegador no ejecuta: un text/html o un SVG servido desde este origen
        // correría con la sesión de la paciente. Cualquier otro tipo, 404 como lo inexistente.
        ArchivoVista archivo = cartilla.documento(user.getId(), id)
                .filter(a -> SERVIBLES.contains(a.tipoMime()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(archivo.tipoMime()))
                // Se abre dentro del registro y no se guarda en ninguna caché. El nosniff lo pone
                // Spring Security en toda respuesta; el sandbox cubre a quien abra la ruta directo.
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore())
                .body(archivo.contenido());
    }
}
