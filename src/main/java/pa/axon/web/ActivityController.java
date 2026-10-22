package pa.axon.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pa.axon.domain.ActivityService;
import pa.axon.security.AxonUser;

/**
 * H5: Actividad (UI.md 13, HU-10). Solo GET: no hay ruta que edite ni borre la bitácora (RS-07,
 * CP-12), y cualquier otro método a /actividad termina en 405 o en el 403 del CSRF. La paciente sale de
 * la sesión, nunca de la solicitud (RS-02).
 */
@Controller
public class ActivityController {

    private final ActivityService actividad;

    public ActivityController(ActivityService actividad) {
        this.actividad = actividad;
    }

    @GetMapping("/actividad")
    public String actividad(@AuthenticationPrincipal AxonUser user, Model model) {
        model.addAttribute("activo", "actividad");
        model.addAttribute("entradas", actividad.deLaPaciente(user.getId()));
        return "paciente/actividad";
    }
}
