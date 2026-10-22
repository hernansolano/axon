package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-06 (HU-04): la cartilla aparece agrupada por categoría, en el orden de ESPEC §2, y dentro de cada
 * grupo los registros van del más reciente al más antiguo.
 */
class Cp06CartillaAgrupadaE2E extends E2EBase {

    private static final List<String> ORDEN = List.of("diagnosticos", "medicamentos", "laboratorios", "vacunas", "otros");
    private static final DateTimeFormatter MES_ANIO = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-PA"));

    @Test
    void losGruposSiguenElOrdenDeLasCategoriasYCadaUnoVaPorFecha() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);

        List<WebElement> grupos = ana.findElements(testidQueEmpieza("grupo-"));
        List<String> nombres = grupos.stream().map(g -> g.getAttribute("data-testid").substring("grupo-".length())).toList();
        // Ana tiene registros sembrados en las cinco categorías (ESPEC §8).
        assertEquals(ORDEN, nombres);

        for (WebElement grupo : grupos) {
            List<WebElement> registros = grupo.findElements(testidQueEmpieza("registro-")).stream()
                    .filter(r -> r.getAttribute("data-testid").matches("registro-\\d+")).toList();
            assertFalse(registros.isEmpty(), "Un grupo sin registros no se muestra");
            YearMonth anterior = YearMonth.of(9999, 12);
            for (WebElement registro : registros) {
                YearMonth fecha = mes(registro.findElement(testid("registro-fecha")).getText());
                assertFalse(fecha.isAfter(anterior), grupo.getAttribute("data-testid") + " no va del más reciente al más antiguo");
                anterior = fecha;
            }
        }
    }

    @Test
    void cadaRegistroEstaEnElGrupoDeSuCategoria() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);

        WebElement medicamentos = visible(ana, "grupo-medicamentos");
        String texto = medicamentos.getText();
        assertTrue(texto.contains("Losartán 50 mg"));
        assertTrue(texto.contains("Penicilina, alergia"));
        assertFalse(texto.contains("Hemograma completo"), "Un laboratorio no va en medicamentos");
        assertTrue(visible(ana, "grupo-laboratorios").getText().contains("Hemograma completo"));
    }

    /** «Agosto 2026 · Policlínica Villa Esperanza» → 2026-08. */
    private static YearMonth mes(String meta) {
        String fecha = meta.split("·")[0].trim().toLowerCase(Locale.ROOT);
        return YearMonth.parse(fecha, MES_ANIO);
    }
}
