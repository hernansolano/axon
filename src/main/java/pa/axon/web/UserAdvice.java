package pa.axon.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import pa.axon.security.AxonUser;

/** Pone a quien inició sesión en el modelo de toda vista, sin su hash, para la barra de navegación. */
@ControllerAdvice
public class UserAdvice {

    @ModelAttribute("usuario")
    public UsuarioVista usuario(@AuthenticationPrincipal AxonUser user) {
        return UsuarioVista.de(user);
    }
}
