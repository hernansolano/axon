package pa.axon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Una fila de la bitácora. Solo se inserta: Hibernate no la actualiza ({@link Immutable}) y la base
 * rechaza cambios y borrados con un disparador (RS-07). La paciente la ve en Actividad (H5).
 */
@Entity
@Immutable
@Table(name = "bitacora")
public class AuditEntry {

    public enum Type {
        PERMISO_CREADO, PERMISO_CANJEADO, CONSULTA, CONSULTA_DENEGADA,
        PERMISO_REVOCADO, EMERGENCIA, REGISTRO_ELIMINADO, SOLICITUD_AMPLIACION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paciente_id", nullable = false)
    private Long patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_evento", nullable = false)
    private Type type;

    @Column(name = "profesional_id")
    private Long professionalId;

    @Column(name = "permiso_id")
    private Long permissionId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categorias", columnDefinition = "text[]")
    private String[] categories;

    @Column(name = "detalle")
    private String detail;

    @Column(name = "creado_en", nullable = false)
    private Instant createdAt;

    protected AuditEntry() {
    }

    public AuditEntry(Long patientId, Type type, Long professionalId, Long permissionId,
                      Collection<Category> categories, String detail, Instant createdAt) {
        this.patientId = patientId;
        this.type = type;
        this.professionalId = professionalId;
        this.permissionId = permissionId;
        this.categories = categories == null ? null : categories.stream().map(Category::name).toArray(String[]::new);
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Type getType() {
        return type;
    }

    public Long getProfessionalId() {
        return professionalId;
    }

    public Long getPermissionId() {
        return permissionId;
    }

    public String[] getCategories() {
        return categories == null ? null : categories.clone();
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
