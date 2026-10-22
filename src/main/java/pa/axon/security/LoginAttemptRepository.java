package pa.axon.security;

import java.time.Instant;
import org.springframework.data.repository.Repository;

/** Solo se inserta: ni lectura masiva ni borrado (RS-07). */
public interface LoginAttemptRepository extends Repository<LoginAttempt, Long> {

    LoginAttempt save(LoginAttempt attempt);

    /** Cuántos fallos de una cuenta con ese motivo hubo desde un momento: solo un número, nunca las filas. */
    long countByAccountTypeAndIdentifierAndReasonStartingWithAndCreatedAtAfter(
            AccountType accountType, String identifier, String reasonPrefix, Instant since);
}
