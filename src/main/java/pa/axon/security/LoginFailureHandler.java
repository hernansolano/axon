package pa.axon.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import pa.axon.domain.Cedula;

/**
 * Registra el intento y devuelve a la misma puerta con un aviso genérico: no dice si falló la
 * cuenta o la clave (HU-01, criterio 2). Hay uno por cadena, así se sabe qué puerta se usó.
 */
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final AccountType type;
    private final String identifierParam;
    private final String redirectTo;
    private final LoginAttemptService attempts;

    public LoginFailureHandler(AccountType type, String identifierParam, String redirectTo,
                               LoginAttemptService attempts) {
        this.type = type;
        this.identifierParam = identifierParam;
        this.redirectTo = redirectTo;
        this.attempts = attempts;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String identifier = request.getParameter(identifierParam);
        // La cédula se anota como se busca (DEC-24): «pe-8-1234» y «PE-8-1234» son el mismo intento.
        if (type == AccountType.PACIENTE) {
            identifier = Cedula.normalizar(identifier);
        }
        attempts.record(type, identifier, request.getRemoteAddr(), exception.getClass().getSimpleName());
        response.sendRedirect(request.getContextPath() + redirectTo);
    }
}
