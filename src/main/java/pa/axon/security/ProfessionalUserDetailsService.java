package pa.axon.security;

import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import pa.axon.domain.ProfessionalRepository;

/** Busca profesionales por idoneidad, sin distinguir mayúsculas. Lo crea SecurityConfig. Tener cuenta no da acceso a nada (RS-03). */
public class ProfessionalUserDetailsService implements UserDetailsService {

    private final ProfessionalRepository professionals;

    public ProfessionalUserDetailsService(ProfessionalRepository professionals) {
        this.professionals = professionals;
    }

    @Override
    public UserDetails loadUserByUsername(String idoneidad) throws UsernameNotFoundException {
        return professionals.findByIdoneidad(idoneidad.trim().toUpperCase(Locale.ROOT))
                .map(p -> AxonUser.professional(p.getId(), p.getIdoneidad(), p.getNombre(), p.getInstitucion(), p.getHashClave()))
                .orElseThrow(() -> new UsernameNotFoundException("profesional"));
    }
}
