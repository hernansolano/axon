package pa.axon.domain;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Solo inserción (RS-07): sin delete ni métodos que cambien filas. Actividad solo lee (H5). */
public interface AuditRepository extends Repository<AuditEntry, Long> {

    AuditEntry save(AuditEntry entry);

    /** La actividad de una paciente, de la más reciente a la más antigua (HU-10, criterio 1). */
    List<AuditEntry> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId);

    /** Si ese profesional ya pidió agregar esa categoría a ese permiso (PB-19): se anota una sola vez. */
    @Query(value = "select count(*) > 0 from bitacora where permiso_id = :permiso and profesional_id = :profesional"
            + " and tipo_evento = 'SOLICITUD_AMPLIACION' and :categoria = any(categorias)", nativeQuery = true)
    boolean alreadyRequested(@Param("permiso") Long permissionId, @Param("profesional") Long professionalId,
                             @Param("categoria") String category);
}
