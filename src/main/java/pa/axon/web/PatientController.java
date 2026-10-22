package pa.axon.web;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import pa.axon.domain.CartillaService;
import pa.axon.domain.CartillaService.NuevoRegistro;
import pa.axon.domain.Category;
import pa.axon.domain.DocumentUpload;
import pa.axon.security.AxonUser;

/**
 * Pantallas del paciente. La cartilla, su detalle y agregar un registro leen y escriben datos;
 * compartir y permisos están en {@link PermissionController}, y actividad en {@link ActivityController}.
 * Emergencia es el marco de navegación y se conecta en H9 (ver PLAN.md).
 */
@Controller
public class PatientController {

    /** Campos del formulario de agregar, en el orden en que aparecen en pantalla (UI.md, 07b). */
    private static final List<String> CAMPOS = List.of("titulo", "categoria", "fecha", "institucion");

    /** Lo que se ve cuando falta un dato o no se entiende (una fecha que no es fecha, una categoría que no existe). */
    private static final Map<String, String> FALTA = Map.of(
            "titulo", "Qué dice el papel: el medicamento, la vacuna o el examen.",
            "categoria", "De qué se trata, entre las cinco categorías.",
            "fecha", "La fecha que aparece en el papel.");

    private final CartillaService cartilla;

    public PatientController(CartillaService cartilla) {
        this.cartilla = cartilla;
    }

    @GetMapping("/cartilla")
    public String cartilla(@AuthenticationPrincipal AxonUser user, Model model) {
        model.addAttribute("activo", "cartilla");
        // El id sale de la sesión, nunca de un parámetro de la solicitud (RS-02).
        model.addAttribute("cartilla", cartilla.deLaPaciente(user.getId()));
        return "paciente/cartilla";
    }

    /**
     * 404 y no 403 cuando el registro es de otra paciente: un 403 confirmaría que existe (CP-02).
     * Un id que no es número no entra aquí y también termina en 404.
     */
    @GetMapping("/cartilla/{id:\\d{1,18}}")
    public String detalle(@AuthenticationPrincipal AxonUser user, @PathVariable Long id, Model model) {
        model.addAttribute("activo", "cartilla");
        model.addAttribute("registro", cartilla.detalle(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "paciente/detalle";
    }

    @GetMapping("/cartilla/nuevo")
    public String nuevo(Model model) {
        return formulario(model, new RecordForm(), List.of(), Map.of());
    }

    /**
     * HU-03. Si falta algo, se listan arriba del formulario los datos que faltan, por su nombre y en el
     * orden de los campos (UI.md, 07b), y no se guarda nada. El archivo no se conserva entre intentos:
     * se pide adjuntarlo otra vez.
     */
    @PostMapping("/cartilla/nuevo")
    public String agregar(@AuthenticationPrincipal AxonUser user,
                          @Valid @ModelAttribute("form") RecordForm form, BindingResult result,
                          @RequestParam(name = "foto", required = false) MultipartFile foto,
                          @RequestParam(name = "archivo", required = false) MultipartFile archivo,
                          Model model) throws IOException {
        List<String> problemas = new ArrayList<>();
        DocumentUpload documento = documento(foto, archivo, problemas);
        // Una fecha fuera del rango provisional (P-20) cuenta como una fecha que no se entiende (DEC-26).
        if (!result.hasFieldErrors("fecha") && !form.fechaEnRango()) {
            result.rejectValue("fecha", "Rango", FALTA.get("fecha"));
        }
        Map<String, String> faltan = new LinkedHashMap<>();
        for (String campo : CAMPOS) {
            FieldError error = result.getFieldError(campo);
            if (error == null) {
                continue;
            }
            if (FALTA.containsKey(campo) && !"Size".equals(error.getCode())) {
                faltan.put(campo, FALTA.get(campo));
            } else {
                problemas.add(error.getDefaultMessage());
            }
        }
        if (!problemas.isEmpty() || !faltan.isEmpty()) {
            if (documento != null) {
                problemas.add(0, "Vuelve a adjuntar el documento: no se guarda hasta que el registro esté completo.");
            }
            return formulario(model, form, problemas, faltan);
        }
        // El id de la paciente sale de la sesión, nunca de la solicitud (RS-02).
        cartilla.agregar(user.getId(),
                new NuevoRegistro(form.getCategoria(), form.getTitulo(), form.getFecha(), form.getInstitucion()),
                documento);
        return "redirect:/cartilla";
    }

    /**
     * El documento es opcional y puede venir de la cámara o de otro archivo (galería, archivos o PDF,
     * DEC-27), pero uno solo. El tipo lo decide el contenido, no la extensión ni lo que diga el navegador.
     */
    private static DocumentUpload documento(MultipartFile foto, MultipartFile otro, List<String> problemas)
            throws IOException {
        boolean hayFoto = foto != null && !foto.isEmpty();
        boolean hayOtro = otro != null && !otro.isEmpty();
        if (hayFoto && hayOtro) {
            problemas.add("Adjunta un solo documento: la foto o el otro archivo.");
            return null;
        }
        MultipartFile archivo = hayFoto ? foto : hayOtro ? otro : null;
        if (archivo == null) {
            return null;
        }
        if (archivo.getSize() > DocumentUpload.MAX_BYTES) {
            problemas.add("El documento pesa más de 10 MB.");
            return null;
        }
        Optional<DocumentUpload> documento = DocumentUpload.of(archivo.getOriginalFilename(), archivo.getBytes());
        if (documento.isEmpty()) {
            problemas.add("El documento tiene que ser una foto (JPG, PNG o WebP) o un PDF.");
        }
        return documento.orElse(null);
    }

    private static String formulario(Model model, RecordForm form, List<String> problemas, Map<String, String> faltan) {
        model.addAttribute("activo", "cartilla");
        model.addAttribute("form", form);
        model.addAttribute("categorias", Category.values());
        model.addAttribute("problemas", problemas);
        model.addAttribute("faltan", faltan);
        return "paciente/agregar";
    }

    @GetMapping("/emergencia")
    public String emergencia(Model model) {
        return pendiente(model, "emergencia", "Emergencia", "Ficha de emergencia",
                "Si llegas a una urgencia y no puedes hablar, esto es lo único que un médico podrá ver, "
                        + "sin código de por medio. Lo eliges tú, línea por línea.", "H9");
    }

    private static String pendiente(Model model, String activo, String etiqueta, String titulo,
                                    String subtitulo, String hito) {
        model.addAttribute("activo", activo);
        model.addAttribute("etiqueta", etiqueta);
        model.addAttribute("titulo", titulo);
        model.addAttribute("subtitulo", subtitulo);
        model.addAttribute("hito", hito);
        return "paciente/pendiente";
    }
}
