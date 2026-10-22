package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;

/**
 * El recorrido de aceptación de la fase 1 (PLAN.md), de principio a fin y sin tocar la base: es el
 * guion del video de la Sprint Review. Cada paso lleva el número que tiene en PLAN.md.
 */
class RecorridoFase1E2E extends E2EBase {

    @Test
    void recorridoDeAceptacionDeLaFase1() {
        // 1. Ana entra con su cédula y ve su cartilla, con las procedencias visibles.
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        assertFalse(ana.findElements(By.cssSelector("[data-procedencia='con-documento']")).isEmpty());
        assertFalse(ana.findElements(By.cssSelector("[data-procedencia='autodeclarado']")).isEmpty());

        // 2. Abre un registro y ve el documento original.
        WebElement conDocumento = ana.findElement(By.cssSelector("[data-procedencia='con-documento']"));
        long registro = Long.parseLong(conDocumento.getAttribute("data-testid").substring("procedencia-".length()));
        visible(ana, "registro-" + registro).click();
        espera(ana, 15).until(d -> "listo".equals(d.findElement(testid("visor-documento")).getAttribute("data-estado")));

        // 3. Comparte solo medicamentos y alergias, por dos horas.
        Permiso permiso = compartir(ana, 120, "medicamentos");

        // 4. La Dra. Rivera entra con su idoneidad. No ve ninguna cartilla.
        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        assertTrue(rivera.findElements(testidQueEmpieza("grupo-")).isEmpty());
        assertTrue(rivera.findElements(testidQueEmpieza("registro-")).isEmpty());

        // 5. Canjea el código y ve medicamentos y alergias.
        canjear(rivera, permiso.codigo());
        visible(rivera, "grupo-medicamentos");
        for (String otra : List.of("diagnosticos", "laboratorios", "vacunas", "otros")) {
            sinElemento(rivera, "grupo-" + otra);
        }
        String consulta = rivera.getWindowHandle();
        ((JavascriptExecutor) rivera).executeScript("window.sinRecargar = true;");

        // 6. Pide vacunas y recibe la denegación explicada. Lo hace en otra pestaña: la consulta queda abierta
        //    para el paso 8, y volver a ella la cargaría de nuevo (otra entrada en la actividad).
        String pedirVacunas = visible(rivera, "pedir-vacunas").getAttribute("href");
        rivera.switchTo().newWindow(WindowType.TAB);
        rivera.get(pedirVacunas);
        visible(rivera, "alerta-fuera-de-alcance");
        sinElemento(rivera, "grupo-vacunas");
        rivera.close();
        rivera.switchTo().window(consulta);

        // 7. Ana revoca el permiso.
        ir(ana, "/permisos");
        visible(ana, "revocar-" + permiso.id()).click();
        visible(ana, "aviso-revocado");

        // 8. Sin que la Dra. Rivera recargue, su pantalla deja de mostrar la cartilla (CR-15).
        espera(rivera, 15).until(d -> d.findElement(testid("consulta-cortada")).isDisplayed());
        assertTrue(rivera.findElements(testidQueEmpieza("grupo-")).isEmpty());
        assertEquals(Boolean.TRUE, ((JavascriptExecutor) rivera).executeScript("return window.sinRecargar === true;"));

        // 9. Ana abre Actividad y encuentra los cinco eventos, incluido el denegado.
        visible(ana, "nav-actividad").click();
        List<WebElement> entradas = ana.findElements(testidQueEmpieza("evento-")).stream()
                .filter(e -> e.getAttribute("data-marca") != null).limit(5).toList();
        assertEquals(List.of("propia", "denegada", "consulta", "consulta", "propia"),
                entradas.stream().map(e -> e.getAttribute("data-marca")).toList());
        assertEquals("Revocaste el permiso " + permiso.codigo() + " antes de que venciera", que(entradas.get(0)));
        assertTrue(que(entradas.get(1)).contains("vacunas"));
        assertTrue(que(entradas.get(2)).startsWith("Vio medicamentos y alergias"));
        assertTrue(que(entradas.get(3)).startsWith("Canjeó " + permiso.codigo()));
        assertTrue(que(entradas.get(4)).startsWith("Generaste el código " + permiso.codigo()));
        for (WebElement e : entradas.subList(1, 4)) {
            assertTrue(e.findElement(testid("evento-quien")).getText().contains("Dra. Rivera"));
        }
    }

    private static String que(WebElement entrada) {
        return entrada.findElement(testid("evento-que")).getText();
    }
}
