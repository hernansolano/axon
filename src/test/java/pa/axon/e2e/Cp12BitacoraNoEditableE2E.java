package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-12 (HU-10): la bitácora no se puede editar desde la interfaz. Actividad no trae nada con qué editar
 * ni borrar, y ni con la sesión y el CSRF de la paciente otro método que GET cambia algo.
 */
class Cp12BitacoraNoEditableE2E extends E2EBase {

    @Test
    void actividadNoTieneConQueEditarNiBorrar() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        compartir(ana, 15, "vacunas");

        visible(ana, "nav-actividad").click();
        visible(ana, "lista-actividad");
        WebElement main = ana.findElement(By.tagName("main"));
        for (String etiqueta : List.of("form", "button", "input", "textarea", "select", "[contenteditable]")) {
            assertTrue(main.findElements(By.cssSelector(etiqueta)).isEmpty(), "Actividad tiene " + etiqueta);
        }
        // La entrada más reciente es la que acaba de generar, y es suya.
        WebElement primera = ana.findElements(testidQueEmpieza("evento-")).stream()
                .filter(e -> e.getAttribute("data-marca") != null).findFirst().orElseThrow();
        assertEquals("propia", primera.getAttribute("data-marca"));
        assertTrue(primera.findElement(testid("evento-que")).getText().startsWith("Generaste el código"));
    }

    @Test
    void niConLaSesionYElCsrfDeLaPacienteSeCambiaNada() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        ir(ana, "/actividad");
        String antes = visible(ana, "actividad-conteo").getText();
        String primeraAntes = primeraEntrada(ana);

        // El token CSRF de la página (el del formulario de salir) va en cada intento.
        @SuppressWarnings("unchecked")
        Map<String, Object> respuestas = (Map<String, Object>) ((JavascriptExecutor) ana).executeAsyncScript("""
                const listo = arguments[arguments.length - 1];
                const token = document.querySelector('input[name=_csrf]').value;
                const id = document.querySelector('[data-marca]').getAttribute('data-testid').replace('evento-', '');
                const intentos = [['POST', '/actividad'], ['PUT', '/actividad'], ['PATCH', '/actividad'],
                    ['DELETE', '/actividad'], ['POST', '/actividad/' + id + '/eliminar'], ['DELETE', '/actividad/' + id],
                    ['POST', '/actividad/' + id + '/editar']];
                Promise.all(intentos.map(([m, u]) => fetch(u, {method: m, credentials: 'same-origin', redirect: 'manual',
                        headers: {'X-CSRF-TOKEN': token, 'Content-Type': 'application/x-www-form-urlencoded'},
                        body: '_csrf=' + encodeURIComponent(token) + '&detalle=cambiado'})
                    .then(r => [m + ' ' + u, r.status])))
                    .then(rs => listo(Object.fromEntries(rs)));
                """);

        respuestas.forEach((intento, codigo) -> {
            int c = ((Number) codigo).intValue();
            assertTrue(c == 404 || c == 405, intento + " respondió " + c);
        });

        ana.navigate().refresh();
        assertEquals(antes, visible(ana, "actividad-conteo").getText());
        assertEquals(primeraAntes, primeraEntrada(ana));
        assertFalse(ana.getPageSource().contains("cambiado"));
    }

    private static String primeraEntrada(WebDriver driver) {
        WebElement e = driver.findElements(testidQueEmpieza("evento-")).stream()
                .filter(x -> x.getAttribute("data-marca") != null).findFirst().orElseThrow();
        return e.getAttribute("data-testid") + "|" + e.getText();
    }
}
