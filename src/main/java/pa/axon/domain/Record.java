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
import java.time.LocalDate;

/** Un registro de la cartilla. La procedencia no se guarda: la deriva la existencia de un {@link Document}. */
@Entity
@Table(name = "registro")
public class Record {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paciente_id", nullable = false)
    private Long patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "categoria", nullable = false)
    private Category category;

    @Column(name = "titulo", nullable = false)
    private String title;

    @Column(name = "detalle")
    private String detail;

    @Column(name = "fecha_documento", nullable = false)
    private LocalDate documentDate;

    @Column(name = "institucion_origen")
    private String originInstitution;

    /** Al crear lo pone la base; al corregir, {@link #corregir}. */
    @Column(name = "actualizado_en", insertable = false)
    private Instant updatedAt;

    protected Record() {
    }

    public Record(Long patientId, Category category, String title, String detail,
                  LocalDate documentDate, String originInstitution) {
        this.patientId = patientId;
        this.category = category;
        this.title = title;
        this.detail = detail;
        this.documentDate = documentDate;
        this.originInstitution = originInstitution;
    }

    /** HU-05: lo que se puede corregir es la categoría, la fecha y el origen. El título queda como está. */
    public void corregir(Category category, LocalDate documentDate, String originInstitution, Instant now) {
        this.category = category;
        this.documentDate = documentDate;
        this.originInstitution = originInstitution;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Category getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDate getDocumentDate() {
        return documentDate;
    }

    public String getOriginInstitution() {
        return originInstitution;
    }
}
