package pa.axon.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import pa.axon.domain.Category;

/**
 * Formulario para agregar un registro (UI.md, 07 y 07b). Los mensajes de lo que falta son los de
 * UI.md, tal cual. El documento no está aquí: llega como archivo aparte a {@link PatientController}.
 * No hay campo de paciente: el registro es siempre de la sesión (RS-02). Los textos llegan sin
 * caracteres de control, que PostgreSQL rechaza (un carácter nulo daría un 500), y la fecha tiene un rango
 * provisional (P-20) que revisa el controlador.
 */
public class RecordForm {

    /** Rango provisional de la fecha del papel (P-20): fuera de él la base guarda absurdos o falla. */
    static final LocalDate FECHA_MIN = LocalDate.of(1900, 1, 1);
    static final LocalDate FECHA_MAX = LocalDate.of(2100, 12, 31);

    @NotBlank(message = "Qué dice el papel: el medicamento, la vacuna o el examen.")
    @Size(max = 160, message = "Qué dice el papel puede tener hasta 160 caracteres.")
    private String titulo;

    @NotNull(message = "De qué se trata, entre las cinco categorías.")
    private Category categoria;

    @NotNull(message = "La fecha que aparece en el papel.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fecha;

    @Size(max = 160, message = "Dónde se lo dieron puede tener hasta 160 caracteres.")
    private String institucion;

    public String getTitulo() {
        return titulo;
    }

    public void setTitulo(String titulo) {
        this.titulo = limpio(titulo);
    }

    public Category getCategoria() {
        return categoria;
    }

    public void setCategoria(Category categoria) {
        this.categoria = categoria;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public void setFecha(LocalDate fecha) {
        this.fecha = fecha;
    }

    public String getInstitucion() {
        return institucion;
    }

    public void setInstitucion(String institucion) {
        this.institucion = limpio(institucion);
    }

    /** Un salto de línea o un tabulador pegados quedan como espacio; los demás caracteres de control, también. */
    private static String limpio(String texto) {
        return texto == null ? null : texto.replaceAll("\\p{Cntrl}", " ").strip();
    }

    boolean fechaEnRango() {
        return fecha == null || !(fecha.isBefore(FECHA_MIN) || fecha.isAfter(FECHA_MAX));
    }
}
