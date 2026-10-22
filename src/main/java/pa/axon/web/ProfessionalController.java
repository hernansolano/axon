package pa.axon.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import pa.axon.domain.CartillaService.ArchivoVista;
import pa.axon.domain.Category;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.ConsultaService.Acceso;
import pa.axon.domain.ConsultaService.Canje;
import pa.axon.domain.ConsultaService.Pedido;
import pa.axon.domain.ConsultaService.Resultado;
import pa.axon.security.AxonUser;

/**
 * H4 (HU-08): el canje y la consulta autorizada. La pantalla de inicio es un campo y nada más: no
 * existe, ni debe existir, una ruta que busque o liste pacientes (RS-14). Todo se busca por el permiso
 * que canjeó el profesional de la sesión; uno ajeno da 404 igual que uno que no existe.
 */
@Controller
public class ProfessionalController {

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Set<String> SERVIBLES = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");

    private final ConsultaService consultas;

    public ProfessionalController(ConsultaService consultas) {
        this.consultas = consultas;
    }

    @GetMapping("/pro/canjear")
    public String canjear() {
        return "pro/canjear";
    }

    @PostMapping("/pro/canjear")
    public String canjear(@AuthenticationPrincipal AxonUser user, @RequestParam(name = "codigo", required = false) String codigo,
                          HttpServletRequest request, Model model) {
        Canje canje = consultas.canjear(user.getId(), user.getIdentifier(), codigo, request.getRemoteAddr());
        if (canje.resultado() == Resultado.CANJEADO) {
            return "redirect:/pro/consulta/" + canje.permisoId();
        }
        model.addAttribute("error", canje.resultado());
        // Se devuelve lo tecleado solo si cabe en el campo; el servidor ya lo limpió para buscar.
        model.addAttribute("codigo", codigo != null && codigo.length() <= 7 ? codigo : "");
        return "pro/canjear";
    }

    @GetMapping("/pro/consulta/{id:\\d{1,18}}")
    public String consulta(@AuthenticationPrincipal AxonUser user, @PathVariable Long id, Model model) {
        Acceso acceso = consultas.consulta(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("acceso", acceso);
        model.addAttribute("permisoId", id);
        return "pro/consulta";
    }

    /** DEC-30: solo el estado, para que la consulta abierta se vacíe sola. Nada de la cartilla. */
    @GetMapping("/pro/consulta/{id:\\d{1,18}}/estado")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> estado(@AuthenticationPrincipal AxonUser user, @PathVariable Long id) {
        ConsultaService.Estado estado = consultas.estado(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vigente", estado.vigente());
        body.put("restanteSeg", estado.restanteSeg());
        if (!estado.vigente()) {
            body.put("motivo", estado.corte().getSlug());
            body.put("titulo", estado.corte().getTitulo());
            body.put("texto", estado.corte().getTexto());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** UI.md 18: una categoría fuera del permiso se deniega, se explica y queda anotada. */
    @GetMapping("/pro/consulta/{id:\\d{1,18}}/{categoria:[a-z]{1,20}}")
    public String categoria(@AuthenticationPrincipal AxonUser user, @PathVariable Long id,
                            @PathVariable String categoria, Model model) {
        Category c = Category.fromSlug(categoria).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        consultas.estado(user.getId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return consultas.denegar(user.getId(), id, c)
                .map(pedido -> fuera(model, id, pedido, false))
                // Dentro del alcance, o el permiso ya no sirve: la consulta lo muestra.
                .orElse("redirect:/pro/consulta/" + id);
    }

    /** PB-19 (DEC-33): la solicitud de ampliación queda en la actividad de la paciente. */
    @PostMapping("/pro/consulta/{id:\\d{1,18}}/solicitar/{categoria:[a-z]{1,20}}")
    public String solicitar(@AuthenticationPrincipal AxonUser user, @PathVariable Long id,
                            @PathVariable String categoria, Model model) {
        Category c = Category.fromSlug(categoria).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        consultas.estado(user.getId(), id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return consultas.solicitar(user.getId(), id, c)
                .map(pedido -> fuera(model, id, pedido, true))
                .orElse("redirect:/pro/consulta/" + id);
    }

    @GetMapping("/pro/consulta/{id:\\d{1,18}}/documento/{doc:" + UUID_PATTERN + "}")
    public ResponseEntity<byte[]> documento(@AuthenticationPrincipal AxonUser user, @PathVariable Long id, @PathVariable UUID doc) {
        // Las mismas cabeceras que la ruta de la paciente: inline, sin caché y en sandbox (RS-13).
        ArchivoVista archivo = consultas.documento(user.getId(), id, doc)
                .filter(a -> SERVIBLES.contains(a.tipoMime()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(archivo.tipoMime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore())
                .body(archivo.contenido());
    }

    private static String fuera(Model model, Long id, Pedido pedido, boolean solicitado) {
        model.addAttribute("permisoId", id);
        model.addAttribute("pedido", pedido);
        model.addAttribute("solicitado", solicitado);
        return "pro/fuera";
    }
}
