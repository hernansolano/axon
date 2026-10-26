package pa.axon.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import pa.axon.domain.Category;

/**
 * Formulario para corregir un registro (HU-05): solo categoría, fecha y origen, con los mismos
 * mensajes y límites que agregar ({@link RecordForm}). El título no se corrige. No hay campo de
 * paciente ni de registro: el registro sale de la ruta y se busca entre los de la sesión (RS-02).
 */
public class CorreccionForm {

    @NotNull(message = "De qué se trata, entre las cinco categorías.")
    private Category categoria;

    @NotNull(message = "La fecha que aparece en el papel.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fecha;

    @Size(max = 160, message = "Dónde se lo dieron puede tener hasta 160 caracteres.")
    private String institucion;

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
        this.institucion = institucion == null ? null : institucion.replaceAll("\\p{Cntrl}", " ").strip();
    }

    boolean fechaEnRango() {
        return fecha == null || !(fecha.isBefore(RecordForm.FECHA_MIN) || fecha.isAfter(RecordForm.FECHA_MAX));
    }
}
