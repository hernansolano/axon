package pa.axon.domain;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Sin findAll a propósito: nada puede listar profesionales ni, por extensión, pacientes (RS-14). */
public interface ProfessionalRepository extends Repository<Professional, Long> {

    Optional<Professional> findByIdoneidad(String idoneidad);

    /** Quién canjeó un permiso de la paciente; el id sale del permiso, nunca de la solicitud. */
    Optional<Professional> findById(Long id);

    Professional save(Professional professional);

    long count();
}
