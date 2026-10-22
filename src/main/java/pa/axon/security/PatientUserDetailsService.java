package pa.axon.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import pa.axon.domain.Cedula;
import pa.axon.domain.PatientRepository;

/** Busca pacientes por cédula. Lo crea SecurityConfig solo para el proveedor del paciente. */
public class PatientUserDetailsService implements UserDetailsService {

    private final PatientRepository patients;

    public PatientUserDetailsService(PatientRepository patients) {
        this.patients = patients;
    }

    @Override
    public UserDetails loadUserByUsername(String cedula) throws UsernameNotFoundException {
        // Normalizada como en el registro: «pe-8-1234» entra a la cuenta PE-8-1234 (DEC-24).
        return patients.findByCedula(Cedula.normalizar(cedula))
                .map(p -> AxonUser.patient(p.getId(), p.getCedula(), p.getNombre(), p.getHashClave()))
                .orElseThrow(() -> new UsernameNotFoundException("paciente"));
    }
}
