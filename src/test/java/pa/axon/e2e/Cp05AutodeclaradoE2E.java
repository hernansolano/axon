package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-05 (HU-03, HU-11): un registro sin documento queda marcado como autodeclarado, en la cartilla y en
 * su detalle, y no ofrece ningún documento. En fase 1 se usa uno sembrado (CRITERIOS.md).
 */
class Cp05AutodeclaradoE2E extends E2EBase {

    @Test
    void elRegistroSinDocumentoSeMarcaComoAutodeclarado() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);

        List<WebElement> autodeclarados = ana.findElements(
                By.cssSelector("[data-procedencia='autodeclarado']"));
        assertFalse(autodeclarados.isEmpty(), "El sembrado tiene registros sin documento");
        String marca = autodeclarados.get(0).getAttribute("data-testid");
        long id = Long.parseLong(marca.substring("procedencia-".length()));

        visible(ana, "registro-" + id).click();
        espera(ana, 10).until(d -> ruta(d).equals("/cartilla/" + id));

        assertEquals("autodeclarado", visible(ana, "procedencia-" + id).getAttribute("data-procedencia"));
        assertTrue(visible(ana, "visor-sin-documento").isDisplayed());
        sinElemento(ana, "visor-documento");
        assertFalse(ana.getPageSource().contains("/documento/"), "Un autodeclarado no enlaza ningún documento");
    }

    @Test
    void unRegistroConDocumentoNoSeMarcaComoAutodeclarado() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);

        WebElement conDocumento = ana.findElement(
                By.cssSelector("[data-procedencia='con-documento']"));
        long id = Long.parseLong(conDocumento.getAttribute("data-testid").substring("procedencia-".length()));
        visible(ana, "registro-" + id).click();

        assertEquals("con-documento", visible(ana, "procedencia-" + id).getAttribute("data-procedencia"));
        sinElemento(ana, "visor-sin-documento");
        // El visor termina en el documento, no en el error (HU-11).
        espera(ana, 15).until(d -> "listo".equals(d.findElement(testid("visor-documento")).getAttribute("data-estado")));
    }
}
