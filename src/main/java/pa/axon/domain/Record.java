package pa.axon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
