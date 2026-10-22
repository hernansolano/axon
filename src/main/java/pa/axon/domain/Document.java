package pa.axon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Metadatos del archivo de un registro. El archivo se sirve solo por /documento/{id} (RS-13). */
@Entity
@Table(name = "documento")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "registro_id", nullable = false, unique = true)
    private Long recordId;

    @Column(name = "nombre_original", nullable = false)
    private String originalName;

    @Column(name = "tipo_mime", nullable = false)
    private String mimeType;

    @Column(name = "ruta_almacen", nullable = false, unique = true)
    private UUID storagePath;

    @Column(name = "tamano_bytes", nullable = false)
    private long sizeBytes;

    protected Document() {
    }

    public Document(Long recordId, String originalName, String mimeType, UUID storagePath, long sizeBytes) {
        this.recordId = recordId;
        this.originalName = originalName;
        this.mimeType = mimeType;
        this.storagePath = storagePath;
        this.sizeBytes = sizeBytes;
    }

    public Long getRecordId() {
        return recordId;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public UUID getStoragePath() {
        return storagePath;
    }

    /** Solo para el sembrado de dev, cuando repara un documento que quedó sin archivo (DEC-25). */
    public void updateSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public boolean isPdf() {
        return "application/pdf".equals(mimeType);
    }
}
