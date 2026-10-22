package pa.axon.web;

import pa.axon.security.AxonUser;

/**
 * Lo único de quien inició sesión que ven las plantillas. No lleva el hash de la clave: la
 * plantilla no puede exponer lo que el modelo no contiene (RS-11).
 */
public final class UsuarioVista {

    private final String nombre;
    private final String primerNombre;
    private final String iniciales;
    private final String identifier;

    private UsuarioVista(AxonUser user) {
        this.nombre = user.getNombre();
        this.primerNombre = user.getPrimerNombre();
        this.iniciales = user.getIniciales();
        this.identifier = user.getIdentifier();
    }

    public static UsuarioVista de(AxonUser user) {
        return user == null ? null : new UsuarioVista(user);
    }

    public String getNombre() {
        return nombre;
    }

    public String getPrimerNombre() {
        return primerNombre;
    }

    public String getIniciales() {
        return iniciales;
    }

    /** Cédula (paciente) o idoneidad (profesional). */
    public String getIdentifier() {
        return identifier;
    }
}
