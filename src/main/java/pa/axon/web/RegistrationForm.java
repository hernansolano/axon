package pa.axon.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pa.axon.domain.Cedula;

/**
 * Formulario de registro (UI.md, 01b). La cédula acepta todas las variantes panameñas, sin validar
 * contra el Registro Civil ({@link Cedula}, DEC-24). La clave de al menos 8 caracteres sigue siendo la
 * propuesta provisional de P-15. Las reglas entre campos (confirmación, clave distinta de la cédula,
 * tope de BCrypt) están en {@link RegistrationController}.
 */
public class RegistrationForm {

    @NotBlank(message = "Falta tu cédula.")
    @Pattern(regexp = Cedula.PATTERN, message = "La cédula no tiene el formato 8-912-2043.")
    private String cedula;

    @NotBlank(message = "Falta tu nombre.")
    @Size(max = 120, message = "El nombre puede tener hasta 120 caracteres.")
    private String nombre;

    @NotBlank(message = "Falta tu clave.")
    @Size(min = 8, message = "La clave debe tener al menos 8 caracteres.")
    private String clave;

    @NotBlank(message = "Falta repetir tu clave.")
    private String claveConfirmar;

    public String getCedula() {
        return cedula;
    }

    // Cédula y nombre se recortan; la clave no, porque un espacio puede ser parte de ella. La cédula
    // además va en mayúsculas, igual que en el login.
    public void setCedula(String cedula) {
        this.cedula = Cedula.normalizar(cedula);
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre == null ? null : nombre.trim();
    }

    public String getClave() {
        return clave;
    }

    public void setClave(String clave) {
        this.clave = clave;
    }

    public String getClaveConfirmar() {
        return claveConfirmar;
    }

    public void setClaveConfirmar(String claveConfirmar) {
        this.claveConfirmar = claveConfirmar;
    }
}
