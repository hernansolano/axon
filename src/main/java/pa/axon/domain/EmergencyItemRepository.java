package pa.axon.domain;

import java.util.List;
import org.springframework.data.repository.Repository;

/** Las líneas de la ficha, siempre por paciente: no hay forma de traer las de todos (RS-14). */
public interface EmergencyItemRepository extends Repository<EmergencyItem, Long> {

    List<EmergencyItem> findByPatientId(Long patientId);

    EmergencyItem save(EmergencyItem item);

    void delete(EmergencyItem item);
}
