package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * HU-06 (H9, PB-17): la paciente marca línea por línea, el resumen dice lo único que verá urgencias y,
 * sin nada marcado, que no se verá nada (CP-13 en la interfaz; el de servicios está en CartillaServiceTest).
 * Al terminar deja la ficha de Ana vacía, como la siembra el perfil dev.
 */
class Cp13FichaEmergenciaE2E extends E2EBase {

    @Test
    void marcarResumirYVaciarLaFicha() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        visible(ana, "nav-emergencia").click();
        espera(ana, 10).until(d -> ruta(d).equals("/emergencia"));

        List<WebElement> lineas = ana.findElements(testidQueEmpieza("ficha-registro-"));
        assertTrue(lineas.size() >= 2, "Ana tiene registros sembrados");
        WebElement primera = lineas.get(0);
        String dato = primera.findElement(By.className("ficha-dato")).getText();
        String id = primera.getAttribute("data-testid");

        // Vaciar primero, por si otra corrida la dejó marcada.
        lineas.stream().map(l -> l.findElement(By.cssSelector("input"))).filter(WebElement::isSelected)
                .forEach(i -> i.findElement(By.xpath("..")).click());
        visible(ana, "ficha-vacia");

        primera.click();
        // El resumen cambia mientras marca, antes de guardar.
        assertTrue(visible(ana, "ficha-resumen").getText().contains("En urgencias se verán " + dato));
        visible(ana, "boton-guardar-ficha").click();
        visible(ana, "aviso-ficha-guardada");
        assertTrue(visible(ana, "ficha-resumen").getText().contains(dato));
        assertTrue(ana.findElement(testid(id))
                .findElement(By.cssSelector("input")).isSelected());

        // Desmarcar todo y guardar: la ficha queda vacía.
        ana.findElement(testid(id)).click();
        visible(ana, "boton-guardar-ficha").click();
        visible(ana, "aviso-ficha-guardada");
        assertTrue(visible(ana, "ficha-vacia").isDisplayed());
        assertFalse(ana.findElement(testid("ficha-resumen")).getText().contains("se verán"));
    }
}
