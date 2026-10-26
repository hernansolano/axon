package pa.axon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una línea de la ficha de emergencia (HU-06): un registro que la paciente marcó para que se vea en
 * urgencias. Si el registro se elimina, la línea cae con él (ON DELETE CASCADE).
 */
@Entity
@Table(name = "emergencia_item")
public class EmergencyItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paciente_id", nullable = false)
    private Long patientId;

    @Column(name = "registro_id", nullable = false)
    private Long recordId;

    protected EmergencyItem() {
    }

    public EmergencyItem(Long patientId, Long recordId) {
        this.patientId = patientId;
        this.recordId = recordId;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getRecordId() {
        return recordId;
    }
}
