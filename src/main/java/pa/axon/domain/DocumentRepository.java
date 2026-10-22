package pa.axon.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends Repository<Document, Long> {

    List<Document> findByRecordIdIn(Collection<Long> recordIds);

    Optional<Document> findByRecordId(Long recordId);

    /** El documento solo aparece si su registro es de esa paciente: ajeno e inexistente dan lo mismo (RS-02). */
    @Query("select d from Document d, Record r where r.id = d.recordId"
            + " and d.storagePath = :storagePath and r.patientId = :patientId")
    Optional<Document> findOwned(@Param("storagePath") UUID storagePath, @Param("patientId") Long patientId);

    /** Para el profesional: además de la paciente, el registro tiene que estar en el alcance del permiso (RS-04). */
    @Query("select d from Document d, Record r where r.id = d.recordId and d.storagePath = :storagePath"
            + " and r.patientId = :patientId and r.category in :alcance")
    Optional<Document> findInScope(@Param("storagePath") UUID storagePath, @Param("patientId") Long patientId,
                                   @Param("alcance") Collection<Category> alcance);

    Document save(Document document);
}
