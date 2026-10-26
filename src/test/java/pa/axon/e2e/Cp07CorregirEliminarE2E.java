package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-07 (HU-05, H8): la paciente agrega un registro sin documento, lo corrige adjuntando el papel (DEC-40)
 * y lo elimina tras confirmar. La actividad no pierde ninguna entrada: suma la de la eliminación. Trabaja
 * sobre un registro que crea ella misma, así no toca los datos sembrados que usan las demás pruebas.
 */
class Cp07CorregirEliminarE2E extends E2EBase {

    private static final Path DOCUMENTO = Path.of("src", "main", "resources", "semilla", "carne-vacunas.jpg").toAbsolutePath();

    @Test
    void corregirYEliminarNoBorranLaActividad() {
        String titulo = "Prueba e2e H8 " + UUID.randomUUID().toString().substring(0, 8);
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);

        ir(ana, "/cartilla/nuevo");
        escribir(ana, "campo-titulo", titulo);
        ana.findElement(testid("categoria-otros")).click();
        fecha(ana, "2026-09-15");
        ana.findElement(testid("boton-guardar")).click();
        espera(ana, 15).until(d -> ruta(d).equals("/cartilla"));
        long id = idDe(ana, titulo);

        // Corregir: categoría a Vacunas y el papel adjunto; la procedencia pasa sola a con documento.
        ir(ana, "/cartilla/" + id);
        assertEquals("autodeclarado", visible(ana, "procedencia-" + id).getAttribute("data-procedencia"));
        visible(ana, "registro-adjuntar").click();
        espera(ana, 10).until(d -> ruta(d).equals("/cartilla/" + id + "/editar"));
        ana.findElement(testid("campo-archivo")).sendKeys(DOCUMENTO.toString());
        visible(ana, "documento-adjunto");
        ana.findElement(testid("categoria-vacunas")).click();
        visible(ana, "boton-guardar").click();
        espera(ana, 15).until(d -> ruta(d).equals("/cartilla/" + id));
        assertEquals("con-documento", visible(ana, "procedencia-" + id).getAttribute("data-procedencia"));
        assertEquals("Vacunas", visible(ana, "detalle-categoria").getText());

        ir(ana, "/actividad");
        int antes = conteo(ana);
        List<String> entradasAntes = entradas(ana);

        // Eliminar: primero confirma; cancelar no borra.
        ir(ana, "/cartilla/" + id);
        visible(ana, "registro-eliminar").click();
        visible(ana, "confirmar-eliminar");
        ana.findElement(testid("cancelar-eliminar")).click();
        assertTrue(visible(ana, "detalle-titulo").getText().contains(titulo));
        visible(ana, "registro-eliminar").click();
        visible(ana, "boton-eliminar").click();
        espera(ana, 15).until(d -> ruta(d).equals("/cartilla"));
        assertFalse(ana.getPageSource().contains(titulo));

        // El registro ya no existe, ni su detalle ni su corrección.
        ir(ana, "/cartilla/" + id);
        assertFalse(ana.getPageSource().contains(titulo));

        // CP-07: la actividad no baja, suma una entrada, y la nueva va primero y es tuya.
        ir(ana, "/actividad");
        assertEquals(antes + 1, conteo(ana));
        List<String> entradasDespues = entradas(ana);
        assertTrue(entradasDespues.containsAll(entradasAntes), "se perdió una entrada de la actividad");
        WebElement primera = ana.findElements(testidQueEmpieza("evento-")).stream()
                .filter(e -> e.getAttribute("data-marca") != null).findFirst().orElseThrow();
        assertEquals("propia", primera.getAttribute("data-marca"));
        assertTrue(primera.getText().contains("Eliminaste «" + titulo + "»"));
    }

    @Test
    void loEliminadoSaleDeUnPermisoVigente() {
        // HU-05, criterio 4: el profesional lo veía con un permiso vigente; al recargar, ya no está.
        String titulo = "Prueba e2e H8 permiso " + UUID.randomUUID().toString().substring(0, 8);
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        ir(ana, "/cartilla/nuevo");
        escribir(ana, "campo-titulo", titulo);
        ana.findElement(testid("categoria-otros")).click();
        fecha(ana, "2026-09-15");
        ana.findElement(testid("boton-guardar")).click();
        espera(ana, 15).until(d -> ruta(d).equals("/cartilla"));
        long id = idDe(ana, titulo);
        Permiso permiso = compartir(ana, 15, "otros");

        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        canjear(rivera, permiso.codigo());
        assertTrue(rivera.getPageSource().contains(titulo));

        ir(ana, "/cartilla/" + id + "?eliminar=");
        visible(ana, "boton-eliminar").click();
        espera(ana, 15).until(d -> ruta(d).equals("/cartilla"));

        rivera.navigate().refresh();
        visible(rivera, "alcance-autorizado");
        assertFalse(rivera.getPageSource().contains(titulo));
    }

    @Test
    void unaFechaFuturaNoSeGuarda() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        ir(ana, "/cartilla/nuevo");
        escribir(ana, "campo-titulo", "Prueba e2e fecha futura");
        ana.findElement(testid("categoria-otros")).click();
        fecha(ana, "2099-01-01");
        ana.findElement(testid("boton-guardar")).click();
        assertTrue(visible(ana, "alerta-faltan-datos").getText().contains("La fecha no puede ser posterior a hoy."));
        assertTrue(ruta(ana).startsWith("/cartilla/nuevo"));
    }

    /** El selector de fecha depende del idioma del navegador: el valor va en ISO, como lo manda el formulario. */
    private static void fecha(WebDriver driver, String iso) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].value = arguments[1];",
                driver.findElement(testid("campo-fecha")), iso);
    }

    private static long idDe(WebDriver driver, String titulo) {
        WebElement registro = driver.findElements(testidQueEmpieza("registro-")).stream()
                .filter(r -> r.getAttribute("data-testid").matches("registro-\\d+") && r.getText().contains(titulo))
                .findFirst().orElseThrow(() -> new AssertionError("El registro nuevo no está en la cartilla"));
        return Long.parseLong(registro.getAttribute("data-testid").substring("registro-".length()));
    }

    private static int conteo(WebDriver driver) {
        return Integer.parseInt(visible(driver, "actividad-conteo").getText().replaceAll("\\D", ""));
    }

    private static List<String> entradas(WebDriver driver) {
        return driver.findElements(testidQueEmpieza("evento-")).stream()
                .filter(e -> e.getAttribute("data-marca") != null)
                .map(e -> e.getAttribute("data-testid"))
                .toList();
    }
}
