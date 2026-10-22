package pa.axon.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import pa.axon.security.AxonUser;

/** Las dos puertas de entrada. Si ya hay sesión del rol correcto, se sigue de largo. */
@Controller
public class AuthController {

    @GetMapping("/")
    public String loginPaciente(@AuthenticationPrincipal AxonUser user) {
        if (user != null && AxonUser.PACIENTE.equals(user.getRole())) {
            return "redirect:/cartilla";
        }
        return "paciente/login";
    }

    @GetMapping("/pro")
    public String loginProfesional(@AuthenticationPrincipal AxonUser user) {
        if (user != null && AxonUser.PROFESIONAL.equals(user.getRole())) {
            return "redirect:/pro/canjear";
        }
        return "pro/login";
    }
}
