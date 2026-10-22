package pa.axon.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Un intento fallido de inicio de sesión (RS-01, DEC-14). Solo se inserta. */
@Entity
@Table(name = "intento_fallido")
public class LoginAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_cuenta", nullable = false, updatable = false)
    private AccountType accountType;

    @Column(name = "identificador", nullable = false, updatable = false)
    private String identifier;

    @Column(name = "origen", updatable = false)
    private String origin;

    /** La pone la base al insertar; solo se lee, para el tope de canjes (DEC-32). */
    @Column(name = "creado_en", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "motivo", nullable = false, updatable = false)
    private String reason;

    protected LoginAttempt() {
    }

    public LoginAttempt(AccountType accountType, String identifier, String origin, String reason) {
        this.accountType = accountType;
        this.identifier = identifier;
        this.origin = origin;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getIdentifier() {
        return identifier;
    }

    public String getOrigin() {
        return origin;
    }

    public String getReason() {
        return reason;
    }
}
