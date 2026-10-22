package pa.axon.security;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RS-01: deja constancia de cada intento fallido. Un fallo al guardar no debe abrir ni trabar el
 * login: se rechaza igual y el problema queda en el log del servidor.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final int MAX_IDENTIFIER = 20;

    private final LoginAttemptRepository attempts;

    public LoginAttemptService(LoginAttemptRepository attempts) {
        this.attempts = attempts;
    }

    /**
     * Un canje fallido (H4), en la misma transacción del canje, que ya tiene el bloqueo del profesional:
     * el siguiente canje lo cuenta en cuanto esta termina. Sin segunda conexión, y si no se puede guardar
     * se deshace todo el canje (falla cerrado; guardián, H4).
     */
    public void recordRedeemFailure(AccountType type, String typedIdentifier, String origin, String reason) {
        String identifier = clean(typedIdentifier);
        attempts.save(new LoginAttempt(type, identifier, origin, reason));
        log.warn("Canje fallido ({}): identificador={} motivo={}", type, mask(identifier), reason);
    }

    public void record(AccountType type, String typedIdentifier, String origin, String reason) {
        String identifier = clean(typedIdentifier);
        try {
            attempts.save(new LoginAttempt(type, identifier, origin, reason));
            log.warn("Intento de acceso fallido ({}): identificador={} motivo={}", type, mask(identifier), reason);
        } catch (RuntimeException e) {
            log.error("No se pudo guardar el intento fallido ({}, {}): {}", type, mask(identifier), e.toString());
        }
    }

    /** Los fallos recientes de una cuenta con ese prefijo de motivo (el tope de canjes, DEC-32). */
    public long recentFailures(AccountType type, String identifier, String reasonPrefix, Instant since) {
        return attempts.countByAccountTypeAndIdentifierAndReasonStartingWithAndCreatedAtAfter(
                type, clean(identifier), reasonPrefix, since);
    }

    /** Quita caracteres de control y recorta: el identificador lo escribe quien ataca. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replaceAll("\\p{Cntrl}", "").trim();
        return value.length() > MAX_IDENTIFIER ? value.substring(0, MAX_IDENTIFIER) : value;
    }

    /** En el log solo se ve el final del identificador. */
    static String mask(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "(vacío)";
        }
        return identifier.length() <= 3 ? "***" : "***" + identifier.substring(identifier.length() - 3);
    }
}
