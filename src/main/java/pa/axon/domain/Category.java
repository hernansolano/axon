package pa.axon.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Las cinco categorías de la cartilla (ESPEC §2). El icono viene del diseño v4.
 */
public enum Category {
    DIAGNOSTICOS("Diagnósticos y antecedentes", "stethoscope", "diagnosticos"),
    MEDICAMENTOS("Medicamentos y alergias", "medication", "medicamentos"),
    LABORATORIOS("Laboratorios y estudios", "biotech", "laboratorios"),
    VACUNAS("Vacunas", "vaccines", "vacunas"),
    OTROS("Otros documentos", "folder", "otros");

    private final String label;
    private final String icon;
    private final String slug;

    Category(String label, String icon, String slug) {
        this.label = label;
        this.icon = icon;
        this.slug = slug;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }

    /** Nombre en minúsculas para los data-testid (grupo-medicamentos) y las clases de tono. */
    public String getSlug() {
        return slug;
    }

    /** El nombre dentro de una frase: «medicamentos y alergias». */
    public String getShortLabel() {
        return label.toLowerCase(Locale.ROOT);
    }

    /** La categoría de una ruta como /pro/consulta/7/vacunas; cualquier otro texto es vacío. */
    public static Optional<Category> fromSlug(String slug) {
        return Arrays.stream(values()).filter(c -> c.slug.equals(slug)).findFirst();
    }

    /** «diagnósticos y antecedentes, vacunas y otros documentos», en el orden de las categorías. */
    public static String join(Collection<Category> categories) {
        List<String> names = categories.stream().sorted().map(Category::getShortLabel).toList();
        if (names.isEmpty()) {
            return "nada";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " y " + names.get(names.size() - 1);
    }
}
