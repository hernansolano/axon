package pa.axon.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Solo existen consultas por dueño: no hay forma de traer registros de todos (RS-02). */
public interface RecordRepository extends Repository<Record, Long> {

    /** RF-04: más reciente primero. El filtro por paciente es parte de la consulta, no de la vista. */
    List<Record> findByPatientIdOrderByDocumentDateDescIdDesc(Long patientId);

    /** Cuántos registros tiene la paciente en cada categoría, para la pantalla de compartir (UI.md 09). */
    @Query("select r.category as category, count(r) as total from Record r where r.patientId = :patientId group by r.category")
    List<CategoryCount> countByCategory(@Param("patientId") Long patientId);

    interface CategoryCount {
        Category getCategory();

        long getTotal();
    }

    /** Lo que ve el profesional: el filtro por alcance va en la consulta, no en la vista (RS-04, HU-08). */
    List<Record> findByPatientIdAndCategoryInOrderByDocumentDateDescIdDesc(Long patientId, Collection<Category> categories);

    /** Un registro ajeno y uno que no existe dan lo mismo: vacío. */
    Optional<Record> findByIdAndPatientId(Long id, Long patientId);

    Record save(Record record);
}
