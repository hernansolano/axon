package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * CP-10 (HU-08): pedir una categoría fuera del permiso se deniega, se explica sin mostrar nada de ella, y
 * queda en la actividad de la paciente con el nombre de quien lo pidió.
 */
class Cp10FueraDeAlcanceE2E extends E2EBase {

    @Test
    void pedirVacunasSeDeniegaSeExplicaYQuedaEnLaActividad() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        Permiso permiso = compartir(ana, 120, "medicamentos");

        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        canjear(rivera, permiso.codigo());
        visible(rivera, "pedir-vacunas").click();

        visible(rivera, "alerta-fuera-de-alcance");
        assertTrue(visible(rivera, "alcance-autorizado").getText().toLowerCase().contains("medicamentos"));
        sinElemento(rivera, "grupo-vacunas");
        HttpResponse<String> crudo = crudo(rivera, ruta(rivera));
        assertFalse(crudo.body().contains("Influenza, dosis anual"), "La denegación no puede traer el dato");

        ir(ana, "/actividad");
        WebElement ultima = ana.findElements(testidQueEmpieza("evento-")).stream()
                .filter(e -> e.getAttribute("data-marca") != null)
                .findFirst().orElseThrow();
        assertEquals("denegada", ultima.getAttribute("data-marca"));
        assertTrue(ultima.findElement(testid("evento-quien")).getText().contains("Dra. Rivera"));
        assertTrue(ultima.findElement(testid("evento-que")).getText().contains("vacunas"));
    }

    @Test
    void laSolicitudDeAmpliacionQuedaEnLaActividadYElPermisoNoCambia() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        Permiso permiso = compartir(ana, 15, "medicamentos");

        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        canjear(rivera, permiso.codigo());
        visible(rivera, "pedir-laboratorios").click();
        visible(rivera, "boton-solicitar-ampliacion").click();
        visible(rivera, "solicitud-enviada");

        ir(ana, "/actividad");
        WebElement ultima = ana.findElements(testidQueEmpieza("evento-")).stream()
                .filter(e -> e.getAttribute("data-marca") != null)
                .findFirst().orElseThrow();
        assertEquals("solicitud", ultima.getAttribute("data-marca"));
        assertTrue(ultima.findElement(testid("evento-que")).getText().contains(permiso.codigo()));

        // El permiso sigue siendo solo de medicamentos.
        ir(rivera, "/pro/consulta/" + permiso.id());
        visible(rivera, "grupo-medicamentos");
        sinElemento(rivera, "grupo-laboratorios");
    }
}
