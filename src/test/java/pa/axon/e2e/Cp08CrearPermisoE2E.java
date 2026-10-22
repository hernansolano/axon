package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-08 (HU-07): la paciente crea un permiso con alcance y vigencia, recibe un código válido del
 * alfabeto de ESPEC §4, ve lo que queda fuera, y el permiso aparece activo en su lista.
 */
class Cp08CrearPermisoE2E extends E2EBase {

    @Test
    void creaUnPermisoConAlcanceYVigenciaYUnCodigoValido() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);

        Permiso permiso = compartir(ana, 120, "medicamentos", "vacunas");

        assertTrue(permiso.codigo().matches(CODIGO), "Código fuera del alfabeto: " + permiso.codigo());
        String podraVer = visible(ana, "podra-ver").getText().toLowerCase();
        assertTrue(podraVer.contains("medicamentos y alergias") && podraVer.contains("vacunas"), podraVer);
        String quedaFuera = visible(ana, "queda-fuera").getText().toLowerCase();
        assertTrue(quedaFuera.contains("laboratorios") && quedaFuera.contains("diagnósticos"), quedaFuera);
        visible(ana, "estado-permiso-activo");

        ir(ana, "/permisos");
        WebElement tarjeta = visible(ana, "permiso-" + permiso.id());
        assertEquals("activo", tarjeta.getAttribute("data-estado"));
        assertEquals(permiso.codigo(), tarjeta.findElement(testid("codigo-permiso")).getText());
        // Dos horas: el contador redondea hacia arriba, así que arranca en «2 h 00 min» o justo debajo.
        String restante = tarjeta.findElement(testid("estado-permiso-activo")).getText();
        assertTrue(restante.contains("2 h 00 min") || restante.contains("1 h 59 min"), restante);
        tarjeta.findElement(testid("revocar-" + permiso.id()));
    }

    @Test
    void sinCategoriaNoSeCreaYSeDiceQueFalta() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        ir(ana, "/compartir");
        // El navegador no deja enviar sin categoría; se quita esa ayuda para probar al servidor (RS-12).
        ((JavascriptExecutor) ana).executeScript(
                "document.querySelectorAll('form').forEach(f => f.noValidate = true);"
                        + "document.querySelectorAll('[disabled]').forEach(e => e.disabled = false);");
        visible(ana, "boton-generar-codigo").click();

        assertTrue(visible(ana, "alerta-compartir").getText().contains("Marca al menos una categoría"));
        sinElemento(ana, "codigo-generado");
    }
}
