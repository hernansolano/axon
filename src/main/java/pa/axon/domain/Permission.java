package pa.axon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un permiso que la paciente genera para un profesional (HU-07). En la base solo se guarda si está
 * ACTIVO o REVOCADO: el vencimiento se calcula con la hora, sin nada que lo escriba (ESPEC §2).
 */
@Entity
@Table(name = "permiso")
public class Permission {

    /** Lo que ve la paciente. VENCIDO no existe en la base. */
    public enum Status { ACTIVO, VENCIDO, REVOCADO }

    static final String ACTIVO = "ACTIVO";
    static final String REVOCADO = "REVOCADO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paciente_id", nullable = false)
    private Long patientId;

    @Column(name = "codigo", nullable = false, unique = true)
    private String code;

    /** Los nombres de las categorías del alcance, en el orden de {@link Category}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "alcance", nullable = false, columnDefinition = "text[]")
    private String[] scope;

    @Column(name = "proposito", nullable = false)
    private String purpose;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    @Column(name = "vence_en", nullable = false)
    private Instant expiresAt;

    @Column(name = "estado", nullable = false)
    private String state;

    @Column(name = "profesional_id")
    private Long professionalId;

    @Column(name = "canjeado_en")
    private Instant redeemedAt;

    protected Permission() {
    }

    public Permission(Long patientId, String code, Collection<Category> scope, String purpose,
                      Instant createdAt, Instant expiresAt) {
        this.patientId = patientId;
        this.code = code;
        this.scope = scope.stream().sorted().map(Category::name).toArray(String[]::new);
        this.purpose = purpose;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.state = ACTIVO;
    }

    /** Revocado gana; si no, vence en el instante exacto de {@code vence_en}. */
    public Status status(Instant now) {
        if (REVOCADO.equals(state)) {
            return Status.REVOCADO;
        }
        return now.isBefore(expiresAt) ? Status.ACTIVO : Status.VENCIDO;
    }

    /**
     * Lo asocia al profesional que lo canjea (HU-08, criterio 2). Devuelve true la primera vez; si ya
     * era suyo no cambia nada. Quien llama comprobó antes que está activo y que no es de otro.
     */
    boolean redeem(Long professionalId, Instant now) {
        if (this.professionalId != null) {
            return false;
        }
        this.professionalId = professionalId;
        this.redeemedAt = now;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getCode() {
        return code;
    }

    public List<Category> getScope() {
        return Arrays.stream(scope).map(Category::valueOf).sorted().toList();
    }

    public String getPurpose() {
        return purpose;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }
}
