package pa.axon.web;

import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
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
import pa.axon.domain.CartillaService.Correccion;
import pa.axon.domain.CartillaService.DetalleVista;
import pa.axon.domain.CartillaService.NuevoRegistro;
import pa.axon.domain.Category;
import pa.axon.domain.DocumentUpload;
import pa.axon.security.AxonUser;

/**
 * Pantallas del paciente. La cartilla, su detalle, agregar, corregir y eliminar un registro leen y escriben datos;
 * compartir y permisos están en {@link PermissionController}, y actividad en {@link ActivityController}.
 * Emergencia configura la ficha (HU-06); abrirla desde urgencias es de HU-12.
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

    /** Código del error de fecha futura (DEC-39): no es un dato que falta, va con los demás problemas. */
    private static final String FUTURA = "Futura";

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
    public String detalle(@AuthenticationPrincipal AxonUser user, @PathVariable Long id,
                          @RequestParam(name = "eliminar", required = false) String eliminar, Model model) {
        model.addAttribute("activo", "cartilla");
        model.addAttribute("registro", cartilla.detalle(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        // HU-05, criterio 2: «Eliminar» primero pide confirmación en el mismo detalle; borra solo el POST.
        model.addAttribute("confirmarEliminar", eliminar != null);
        return "paciente/detalle";
    }

    @GetMapping("/cartilla/{id:\\d{1,18}}/editar")
    public String editar(@AuthenticationPrincipal AxonUser user, @PathVariable Long id, Model model) {
        CorreccionForm form = new CorreccionForm();
        Correccion actual = cartilla.correccion(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        form.setCategoria(actual.categoria());
        form.setFecha(actual.fecha());
        form.setInstitucion(actual.institucion());
        return formularioCorregir(user, id, model, form, List.of(), Map.of());
    }

    /**
     * HU-05, criterio 1: corrige categoría, fecha y origen con las mismas reglas que agregar. Si el
     * registro no tenía documento, aquí se adjunta (DEC-40). Un registro ajeno da 404 igual que uno que
     * no existe (criterio 3), y se revisa antes de mirar el formulario.
     */
    @PostMapping("/cartilla/{id:\\d{1,18}}/editar")
    public String corregir(@AuthenticationPrincipal AxonUser user, @PathVariable Long id,
                           @Valid @ModelAttribute("form") CorreccionForm form, BindingResult result,
                           @RequestParam(name = "foto", required = false) MultipartFile foto,
                           @RequestParam(name = "archivo", required = false) MultipartFile archivo,
                           Model model) throws IOException {
        DetalleVista registro = cartilla.detalle(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String> problemas = new ArrayList<>();
        DocumentUpload documento = registro.conDocumento() ? null : documento(foto, archivo, problemas);
        revisarFecha(result, form.fechaEnRango(), form.getFecha());
        Map<String, String> faltan = faltan(result, problemas);
        if (!problemas.isEmpty() || !faltan.isEmpty()) {
            if (documento != null) {
                problemas.add(0, "Vuelve a adjuntar el documento: no se guarda hasta que el registro esté completo.");
            }
            return formularioCorregir(user, id, model, form, problemas, faltan);
        }
        if (!cartilla.corregir(user.getId(), id,
                new Correccion(form.getCategoria(), form.getFecha(), form.getInstitucion()), documento)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:/cartilla/" + id;
    }

    /** HU-05, criterio 2. Ajeno o inexistente: 404, y no se borra nada ni se anota nada. */
    @PostMapping("/cartilla/{id:\\d{1,18}}/eliminar")
    public String eliminar(@AuthenticationPrincipal AxonUser user, @PathVariable Long id) {
        if (!cartilla.eliminar(user.getId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return "redirect:/cartilla";
    }

    private String formularioCorregir(AxonUser user, Long id, Model model, CorreccionForm form,
                                      List<String> problemas, Map<String, String> faltan) {
        model.addAttribute("activo", "cartilla");
        model.addAttribute("registro", cartilla.detalle(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("form", form);
        model.addAttribute("categorias", Category.values());
        model.addAttribute("problemas", problemas);
        model.addAttribute("faltan", faltan);
        model.addAttribute("hoy", cartilla.hoy());
        return "paciente/editar";
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
        revisarFecha(result, form.fechaEnRango(), form.getFecha());
        Map<String, String> faltan = faltan(result, problemas);
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

    /** Antes de 1900 cuenta como una fecha que no se entiende (DEC-26); después de hoy, tiene su mensaje (DEC-39). */
    private void revisarFecha(BindingResult result, boolean enRango, LocalDate fecha) {
        if (result.hasFieldErrors("fecha")) {
            return;
        }
        if (!enRango) {
            result.rejectValue("fecha", "Rango", FALTA.get("fecha"));
        } else if (cartilla.esFutura(fecha)) {
            result.rejectValue("fecha", FUTURA, "La fecha no puede ser posterior a hoy.");
        }
    }

    /**
     * Lo que falta va en la lista de UI.md 07b, en el orden de los campos; lo demás (un texto muy largo,
     * una fecha futura) se suma a los problemas con su propio mensaje. En corregir no hay título, y su
     * campo simplemente no aparece.
     */
    private static Map<String, String> faltan(BindingResult result, List<String> problemas) {
        Map<String, String> faltan = new LinkedHashMap<>();
        for (String campo : CAMPOS) {
            FieldError error = result.getFieldError(campo);
            if (error == null) {
                continue;
            }
            if (FALTA.containsKey(campo) && !"Size".equals(error.getCode()) && !FUTURA.equals(error.getCode())) {
                faltan.put(campo, FALTA.get(campo));
            } else {
                problemas.add(error.getDefaultMessage());
            }
        }
        return faltan;
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

    private String formulario(Model model, RecordForm form, List<String> problemas, Map<String, String> faltan) {
        model.addAttribute("activo", "cartilla");
        model.addAttribute("form", form);
        model.addAttribute("categorias", Category.values());
        model.addAttribute("problemas", problemas);
        model.addAttribute("faltan", faltan);
        model.addAttribute("hoy", cartilla.hoy());
        return "paciente/agregar";
    }

    /** HU-06, UI.md 12: la paciente elige, línea por línea, qué registros verá urgencias. */
    @GetMapping("/emergencia")
    public String emergencia(@AuthenticationPrincipal AxonUser user,
                             @RequestParam(name = "guardada", required = false) String guardada, Model model) {
        model.addAttribute("activo", "emergencia");
        model.addAttribute("ficha", cartilla.ficha(user.getId()));
        model.addAttribute("guardada", guardada != null);
        return "paciente/emergencia";
    }

    /**
     * La ficha queda con exactamente lo marcado; sin nada marcado, vacía (HU-06, criterio 4). Los ids
     * que no son números o no son de la paciente no llegan a la ficha: el servicio solo acepta los suyos.
     */
    @PostMapping("/emergencia")
    public String guardarFicha(@AuthenticationPrincipal AxonUser user,
                               @RequestParam(name = "registro", required = false) List<String> registros) {
        List<Long> ids = registros == null ? List.of() : registros.stream()
                .filter(s -> s.matches("\\d{1,18}")).map(Long::valueOf).distinct().toList();
        cartilla.guardarFicha(user.getId(), ids);
        return "redirect:/emergencia?guardada";
    }
}
