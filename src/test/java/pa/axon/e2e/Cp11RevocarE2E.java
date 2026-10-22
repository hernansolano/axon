package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-11 (HU-09, CR-15): un permiso revocado deja de funcionar de inmediato, y la consulta que la
 * Dra. Rivera tenía abierta se vacía sola, sin que ella recargue (pregunta cada 5 s, DEC-30).
 */
class Cp11RevocarE2E extends E2EBase {

    @Test
    void revocarVaciaLaConsultaAbiertaSinRecargarYYaNoTraeNada() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        Permiso permiso = compartir(ana, 120, "medicamentos");

        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        canjear(rivera, permiso.codigo());
        visible(rivera, "grupo-medicamentos");
        String direccion = rivera.getCurrentUrl();
        // Una marca en la página: si se recargara, desaparecería.
        ((JavascriptExecutor) rivera).executeScript("window.sinRecargar = true;");

        ir(ana, "/permisos");
        visible(ana, "revocar-" + permiso.id()).click();
        visible(ana, "aviso-revocado");
        WebElement tarjeta = visible(ana, "permiso-" + permiso.id());
        assertEquals("revocado", tarjeta.getAttribute("data-estado"));

        // Sin tocar el navegador de la Dra. Rivera: en menos de dos preguntas la cartilla desaparece.
        WebElement corte = espera(rivera, 15).until(d -> {
            WebElement c = d.findElement(testid("consulta-cortada"));
            return c.isDisplayed() ? c : null;
        });
        assertEquals("revocado", corte.getAttribute("data-motivo"));
        assertTrue(rivera.findElements(testidQueEmpieza("grupo-")).isEmpty());
        assertEquals(direccion, rivera.getCurrentUrl());
        assertEquals(Boolean.TRUE, ((JavascriptExecutor) rivera).executeScript("return window.sinRecargar === true;"),
                "La pantalla se recargó: el corte tiene que llegar sin recargar (CR-15)");

        // Volver a abrir la consulta ya no trae nada.
        HttpResponse<String> crudo = crudo(rivera, "/pro/consulta/" + permiso.id());
        assertFalse(crudo.body().contains("Losartán"));
        assertFalse(crudo.body().contains("data-testid=\"grupo-medicamentos\""));
    }

    @Test
    void activoYRevocadoSeDistinguenEnLaLista() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        Permiso activo = compartir(ana, 15, "vacunas");
        Permiso revocado = compartir(ana, 15, "otros");
        ir(ana, "/permisos");
        visible(ana, "revocar-" + revocado.id()).click();
        visible(ana, "aviso-revocado");

        assertEquals("activo", visible(ana, "permiso-" + activo.id()).getAttribute("data-estado"));
        WebElement cortado = visible(ana, "permiso-" + revocado.id());
        assertEquals("revocado", cortado.getAttribute("data-estado"));
        // Revocado ya no se puede revocar otra vez.
        assertTrue(cortado.findElements(testid("revocar-" + revocado.id())).isEmpty());
    }
}
