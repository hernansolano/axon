package pa.axon.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Solo consultas por dueña: nada lista los permisos de todas (RS-02, RS-14). */
public interface PermissionRepository extends Repository<Permission, Long> {

    Permission save(Permission permission);

    boolean existsByCode(String code);

    List<Permission> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId);

    /** Uno ajeno y uno que no existe dan lo mismo: vacío. */
    Optional<Permission> findByIdAndPatientId(Long id, Long patientId);

    /**
     * Lo mismo, con la fila bloqueada hasta el final de la transacción: un canje que llegue a la vez
     * espera, y la bitácora de la revocación nombra a quien tenía el acceso (guardián, H3).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Permission p where p.id = :id and p.patientId = :patientId")
    Optional<Permission> lockOwned(@Param("id") Long id, @Param("patientId") Long patientId);

    /** El canje (H4): la fila queda bloqueada, así una revocación al mismo tiempo espera a que termine. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Permission p where p.code = :code")
    Optional<Permission> lockByCode(@Param("code") String code);

    /** La consulta del profesional: solo un permiso que canjeó él. Uno ajeno y uno inexistente dan lo mismo. */
    Optional<Permission> findByIdAndProfessionalId(Long id, Long professionalId);

    /**
     * Revoca solo si es de la paciente y sigue vigente, en una sola sentencia: si el permiso venció o
     * ya estaba revocado no cambia nada y devuelve 0 (RS-06).
     */
    @Modifying
    @Query("update Permission p set p.state = 'REVOCADO' where p.id = :id and p.patientId = :patientId"
            + " and p.state = 'ACTIVO' and p.expiresAt > :now")
    int revoke(@Param("id") Long id, @Param("patientId") Long patientId, @Param("now") Instant now);
}
