package pa.axon.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Quien inició sesión. Un paciente se identifica por cédula y un profesional por idoneidad; el rol
 * decide a qué mitad de la aplicación entra (RS-02, RS-03).
 */
public class AxonUser implements UserDetails {

    public static final String PACIENTE = "PACIENTE";
    public static final String PROFESIONAL = "PROFESIONAL";

    private final Long id;
    private final String identifier;
    private final String nombre;
    private final String institucion;
    private final String hash;
    private final String role;

    private AxonUser(Long id, String identifier, String nombre, String institucion, String hash, String role) {
        this.id = id;
        this.identifier = identifier;
        this.nombre = nombre;
        this.institucion = institucion;
        this.hash = hash;
        this.role = role;
    }

    public static AxonUser patient(Long id, String cedula, String nombre, String hash) {
        return new AxonUser(id, cedula, nombre, null, hash, PACIENTE);
    }

    public static AxonUser professional(Long id, String idoneidad, String nombre, String institucion, String hash) {
        return new AxonUser(id, idoneidad, nombre, institucion, hash, PROFESIONAL);
    }

    public Long getId() {
        return id;
    }

    /** Cédula (paciente) o idoneidad (profesional). */
    public String getIdentifier() {
        return identifier;
    }

    public String getNombre() {
        return nombre;
    }

    public String getInstitucion() {
        return institucion;
    }

    public String getRole() {
        return role;
    }

    public String getPrimerNombre() {
        int space = nombre.indexOf(' ');
        return space < 0 ? nombre : nombre.substring(0, space);
    }

    /** Dos letras para el avatar: primera del nombre y primera del último apellido. */
    public String getIniciales() {
        String[] parts = nombre.trim().split("\\s+");
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return hash;
    }

    @Override
    public String getUsername() {
        return identifier;
    }
}
