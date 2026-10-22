package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

/**
 * CP-03 (HU-02): tener cuenta profesional no da acceso a nada. Autenticada, la Dra. Rivera solo tiene
 * un campo para canjear; no hay búsqueda, la cartilla de la paciente le está prohibida y una consulta
 * que no canjeó no existe para ella.
 */
class Cp03ProfesionalSinPermisoE2E extends E2EBase {

    @Test
    void entraYSoloVeElCampoDeCanje() {
        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);

        visible(rivera, "campo-codigo");
        // Un formulario con un solo campo de texto: nada para buscar ni listar pacientes.
        assertEquals(1, rivera.findElements(By.cssSelector("main input:not([type=hidden])")).size());
        sinElemento(rivera, "nav-cartilla");
        sinElemento(rivera, "grupo-diagnosticos");
        assertFalse(rivera.getPageSource().contains(ANA_CEDULA));
    }

    @Test
    void lasRutasDeLaPacienteLeEstanProhibidas() {
        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);

        for (String ruta : new String[] {"/cartilla", "/compartir", "/permisos", "/actividad"}) {
            ir(rivera, ruta);
            visible(rivera, "pantalla-403");
            sinElemento(rivera, "saludo");
            HttpResponse<String> crudo = crudo(rivera, ruta);
            assertEquals(403, crudo.statusCode(), ruta);
            assertFalse(crudo.body().contains("data-testid=\"registro-"), ruta);
        }
    }

    @Test
    void unaConsultaQueNoCanjeoNoExiste() {
        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);

        // Probar ids no abre nada: lo que no canjeó responde igual que lo que no existe.
        for (long id : new long[] {999_997, 999_998, 999_999}) {
            HttpResponse<String> crudo = crudo(rivera, "/pro/consulta/" + id);
            assertTrue(crudo.statusCode() == 404, "consulta " + id + " respondió " + crudo.statusCode());
            assertFalse(crudo.body().contains("data-testid=\"registro-"));
        }
        ir(rivera, "/pro/consulta/999999");
        visible(rivera, "pantalla-404");
    }

    @Test
    void unPermisoVigenteQueNoCanjeoTampocoLeAbreNada() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        Permiso permiso = compartir(ana, 15, "medicamentos");

        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        HttpResponse<String> crudo = crudo(rivera, "/pro/consulta/" + permiso.id());

        assertEquals(404, crudo.statusCode());
        assertFalse(crudo.body().contains("data-testid=\"registro-"));
        assertFalse(crudo.body().contains("Losartán"));
    }
}
