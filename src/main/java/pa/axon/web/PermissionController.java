package pa.axon.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pa.axon.domain.Category;
import pa.axon.domain.PermissionService;
import pa.axon.domain.PermissionService.NuevoPermiso;
import pa.axon.domain.ShareDuration;
import pa.axon.security.AxonUser;

/**
 * H3: compartir (UI.md 09), el código generado (10) y la lista de permisos (11) con su revocación
 * (HU-07, HU-09). La paciente sale siempre de la sesión, nunca de la solicitud (RS-02); un permiso
 * ajeno responde 404 igual que uno que no existe.
 */
@Controller
public class PermissionController {

    private final PermissionService permisos;

    public PermissionController(PermissionService permisos) {
        this.permisos = permisos;
    }

    @GetMapping("/compartir")
    public String compartir(@AuthenticationPrincipal AxonUser user, Model model) {
        return formulario(user, model, EnumSet.noneOf(Category.class), ShareDuration.DEFAULT,
                PermissionService.PROPOSITOS.get(0), List.of());
    }

    /**
     * HU-07. Todo se vuelve a revisar aquí: categorías que existan, una de las tres vigencias y un
     * propósito de la lista. Si algo falla se nombra y no se crea nada (DEC-29).
     */
    @PostMapping("/compartir")
    public String crear(@AuthenticationPrincipal AxonUser user,
                        @RequestParam(name = "alcance", required = false) List<String> alcance,
                        @RequestParam(name = "vigencia", required = false) String vigencia,
                        @RequestParam(name = "proposito", required = false) String proposito,
                        Model model) {
        List<String> problemas = new ArrayList<>();
        Set<Category> marcadas = EnumSet.noneOf(Category.class);
        boolean desconocida = false;
        for (String nombre : alcance == null ? List.<String>of() : alcance) {
            Optional<Category> c = Arrays.stream(Category.values()).filter(x -> x.name().equals(nombre)).findFirst();
            c.ifPresent(marcadas::add);
            desconocida |= c.isEmpty();
        }
        if (desconocida) {
            problemas.add("Una de las categorías marcadas no existe. Marca entre las cinco de la lista.");
        } else if (marcadas.isEmpty()) {
            problemas.add("Marca al menos una categoría.");
        }
        Optional<ShareDuration> duracion = ShareDuration.ofMinutes(vigencia);
        if (duracion.isEmpty()) {
            problemas.add("Por cuánto tiempo: 15 min, 2 horas o 24 horas.");
        }
        // List.of no acepta preguntar por null: sin propósito, contains lanzaría NullPointerException y daría 500.
        boolean propositoValido = proposito != null && PermissionService.PROPOSITOS.contains(proposito);
        if (!propositoValido) {
            problemas.add("Para qué: elige una de las opciones de la lista.");
        }
        if (!problemas.isEmpty()) {
            return formulario(user, model, marcadas, duracion.orElse(ShareDuration.DEFAULT),
                    propositoValido ? proposito : PermissionService.PROPOSITOS.get(0), problemas);
        }
        Long id = permisos.crear(user.getId(), new NuevoPermiso(marcadas, duracion.get(), proposito));
        return "redirect:/compartir/" + id;
    }

    @GetMapping("/compartir/{id:\\d{1,18}}")
    public String codigo(@AuthenticationPrincipal AxonUser user, @PathVariable Long id, Model model) {
        model.addAttribute("activo", "compartir");
        model.addAttribute("permiso", permisos.uno(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "paciente/codigo";
    }

    @GetMapping("/permisos")
    public String permisos(@AuthenticationPrincipal AxonUser user, Model model) {
        model.addAttribute("activo", "permisos");
        model.addAttribute("permisos", permisos.deLaPaciente(user.getId()));
        return "paciente/permisos";
    }

    /** HU-09. Vuelve a la lista con el aviso de que el código ya no sirve y la actividad no se borra. */
    @PostMapping("/permisos/{id:\\d{1,18}}/revocar")
    public String revocar(@AuthenticationPrincipal AxonUser user, @PathVariable Long id, RedirectAttributes flash) {
        String aviso = permisos.revocar(user.getId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!aviso.isBlank()) {
            flash.addFlashAttribute("aviso", aviso);
        }
        return "redirect:/permisos";
    }

    private String formulario(AxonUser user, Model model, Set<Category> marcadas, ShareDuration vigencia,
                              String proposito, List<String> problemas) {
        model.addAttribute("activo", "compartir");
        model.addAttribute("opciones", permisos.opciones(user.getId()));
        model.addAttribute("marcadas", marcadas);
        model.addAttribute("resumen", PermissionService.resumen(marcadas));
        model.addAttribute("vigencias", ShareDuration.values());
        model.addAttribute("vigencia", vigencia);
        model.addAttribute("propositos", PermissionService.PROPOSITOS);
        model.addAttribute("proposito", proposito);
        model.addAttribute("problemas", problemas);
        return "paciente/compartir";
    }
}
