package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-04 (HU-03): la paciente guarda un registro con un documento de prueba y los campos mínimos, y queda
 * en su grupo, con documento. Selenium no acciona la cámara: el archivo se carga con sendKeys
 * (HERRAMIENTAS.md). El documento es uno de los sintéticos de semilla/, con su marca «SINTÉTICO».
 */
class Cp04AgregarRegistroE2E extends E2EBase {

    private static final Path DOCUMENTO = Path.of("src", "main", "resources", "semilla", "carne-vacunas.jpg").toAbsolutePath();

    @Test
    void guardaUnRegistroConDocumentoYQuedaEnSuGrupo() {
        String titulo = "Prueba e2e " + UUID.randomUUID().toString().substring(0, 8);
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        visible(ana, "agregar-registro").click();

        ana.findElement(testid("campo-archivo")).sendKeys(DOCUMENTO.toString());
        visible(ana, "documento-adjunto");
        escribir(ana, "campo-titulo", titulo);
        ana.findElement(testid("categoria-vacunas")).click();
        // El selector de fecha depende del idioma del navegador: el valor va en ISO, como lo manda el formulario.
        ((JavascriptExecutor) ana).executeScript("arguments[0].value = '2026-09-15';", ana.findElement(testid("campo-fecha")));
        escribir(ana, "campo-institucion", "Policlínica Villa Esperanza");
        ana.findElement(testid("boton-guardar")).click();

        espera(ana, 15).until(d -> ruta(d).equals("/cartilla"));
        WebElement registro = visible(ana, "grupo-vacunas").findElements(testidQueEmpieza("registro-")).stream()
                .filter(r -> r.getAttribute("data-testid").matches("registro-\\d+") && r.getText().contains(titulo))
                .findFirst().orElseThrow(() -> new AssertionError("El registro nuevo no está en Vacunas"));
        long id = Long.parseLong(registro.getAttribute("data-testid").substring("registro-".length()));
        assertEquals("con-documento", registro.findElement(testid("procedencia-" + id)).getAttribute("data-procedencia"));

        registro.click();
        assertTrue(visible(ana, "detalle-titulo").getText().contains(titulo));
        espera(ana, 15).until(d -> "listo".equals(d.findElement(testid("visor-documento")).getAttribute("data-estado")));
    }

    @Test
    void sinTituloNiCategoriaNoGuardaYNombraLoQueFalta() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        ir(ana, "/cartilla/nuevo");
        ((JavascriptExecutor) ana).executeScript("document.querySelectorAll('form').forEach(f => f.noValidate = true);");
        visible(ana, "boton-guardar").click();

        visible(ana, "alerta-faltan-datos");
        visible(ana, "falta-titulo");
        visible(ana, "falta-categoria");
        assertTrue(ruta(ana).startsWith("/cartilla/nuevo"));
    }
}
