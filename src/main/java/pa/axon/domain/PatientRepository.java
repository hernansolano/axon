package pa.axon.domain;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Solo lo que la aplicación necesita. No extiende JpaRepository a propósito: así no existe un
 * findAll que un controlador pueda usar por error para listar pacientes (RS-14).
 */
public interface PatientRepository extends Repository<Patient, Long> {

    Optional<Patient> findByCedula(String cedula);

    /** El nombre en la cabecera de la consulta; el id sale del permiso canjeado, nunca de la solicitud. */
    Optional<Patient> findById(Long id);

    boolean existsByCedula(String cedula);

    Patient save(Patient patient);

    long count();
}
