package pa.axon.web;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import pa.axon.domain.Cedula;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRegistrationService.Resultado;
import pa.axon.security.AxonUser;

/**
 * Registro de la paciente (HU-13, CR-14). El profesional autenticado no llega aquí: SecurityConfig
 * le responde 403. No hay registro de profesionales (DEC-12).
 */
@Controller
public class RegistrationController {

    /** Orden en que se muestran los errores: el mismo de los campos en pantalla. */
    private static final List<String> CAMPOS = List.of("cedula", "nombre", "clave", "claveConfirmar");

    /** BCrypt ignora lo que pase de 72 bytes, y Spring Security lo rechaza con una excepción. */
    private static final int MAX_BYTES_CLAVE = 72;

    private final PatientRegistrationService registro;

    public RegistrationController(PatientRegistrationService registro) {
        this.registro = registro;
    }

    @GetMapping("/registro")
    public String formulario(@AuthenticationPrincipal AxonUser user, Model model) {
        if (esPaciente(user)) {
            return "redirect:/cartilla";
        }
        model.addAttribute("form", new RegistrationForm());
        return "paciente/registro";
    }

    @PostMapping("/registro")
    public String registrar(@AuthenticationPrincipal AxonUser user,
                            @Valid @ModelAttribute("form") RegistrationForm form, BindingResult result,
                            Model model) {
        if (esPaciente(user)) {
            return "redirect:/cartilla";
        }
        validarClave(form, result);
        List<String> errores = errores(result);
        if (!errores.isEmpty()) {
            model.addAttribute("errores", errores);
            return "paciente/registro";
        }
        if (registro.registrar(form.getCedula(), form.getNombre(), form.getClave()) == Resultado.NO_CREADA) {
            model.addAttribute("noCreada", true);
            return "paciente/registro";
        }
        // Crear la cuenta no abre sesión: la paciente entra por el login (DEC-16).
        return "redirect:/?registrada";
    }

    private static boolean esPaciente(AxonUser user) {
        return user != null && AxonUser.PACIENTE.equals(user.getRole());
    }

    private static void validarClave(RegistrationForm form, BindingResult result) {
        String clave = form.getClave();
        if (!result.hasFieldErrors("clave")) {
            if (clave.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES_CLAVE) {
                result.rejectValue("clave", "Max", "La clave es demasiado larga.");
            } else if (Cedula.normalizar(clave).equals(form.getCedula())) {
                // La cédula ya viene en mayúsculas: «pe-8-1234» como clave también es la cédula.
                result.rejectValue("clave", "Cedula", "La clave no puede ser tu cédula.");
            }
        }
        if (!result.hasFieldErrors("clave") && !result.hasFieldErrors("claveConfirmar")
                && !clave.equals(form.getClaveConfirmar())) {
            result.rejectValue("claveConfirmar", "Igual", "Las dos claves no coinciden.");
        }
    }

    /** Un mensaje por campo: si falta el dato, ese; si no, la primera regla que no cumple. */
    private static List<String> errores(BindingResult result) {
        List<String> mensajes = new ArrayList<>();
        for (String campo : CAMPOS) {
            List<FieldError> delCampo = result.getFieldErrors(campo);
            delCampo.stream()
                    .filter(e -> "NotBlank".equals(e.getCode()))
                    .findFirst()
                    .or(() -> delCampo.stream().findFirst())
                    .ifPresent(e -> mensajes.add(e.getDefaultMessage()));
        }
        return mensajes;
    }
}
