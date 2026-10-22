package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

/**
 * CP-09 (HU-08): la Dra. Rivera canjea el código y ve solo lo autorizado. Lo que no está en el permiso
 * tampoco viaja en el HTML servido: se comprueba sobre la respuesta cruda, no sobre la pantalla.
 */
class Cp09CanjeYAlcanceE2E extends E2EBase {

    /** Títulos sembrados de Ana fuera de «medicamentos y alergias» (ESPEC §8). */
    private static final List<String> FUERA = List.of(
            "Control prenatal, semana 24", "Dolor lumbar recurrente", "Hemograma completo",
            "Influenza, dosis anual", "Constancia de atención");

    @Test
    void canjeaYVeSoloLasCategoriasAutorizadas() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        Permiso permiso = compartir(ana, 120, "medicamentos");

        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        canjear(rivera, permiso.codigo());

        assertEquals("/pro/consulta/" + permiso.id(), ruta(rivera));
        visible(rivera, "aviso-no-expediente");
        visible(rivera, "tiempo-restante");
        String medicamentos = visible(rivera, "grupo-medicamentos").getText();
        assertTrue(medicamentos.contains("Losartán 50 mg") && medicamentos.contains("Penicilina, alergia"));
        for (String otra : List.of("diagnosticos", "laboratorios", "vacunas", "otros")) {
            sinElemento(rivera, "grupo-" + otra);
        }

        HttpResponse<String> crudo = crudo(rivera, "/pro/consulta/" + permiso.id());
        assertEquals(200, crudo.statusCode());
        for (String titulo : FUERA) {
            assertFalse(crudo.body().contains(titulo), "Viajó en el HTML algo fuera del permiso: " + titulo);
        }
        assertFalse(crudo.body().contains("data-testid=\"grupo-laboratorios\""));
    }

    @Test
    void elCanjeNoDistingueMayusculasNiExigeElGuion() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        Permiso permiso = compartir(ana, 15, "vacunas");

        WebDriver rivera = navegador();
        entrarComoProfesional(rivera, RIVERA_IDONEIDAD);
        canjear(rivera, permiso.codigo().replace("-", "").toLowerCase());

        assertEquals("/pro/consulta/" + permiso.id(), ruta(rivera));
        visible(rivera, "grupo-vacunas");
        sinElemento(rivera, "grupo-medicamentos");
    }

    @Test
    void unCodigoQueNoExisteSeDiceYNoAbreNada() {
        // Con el Dr. Solís: cada canje fallido cuenta para el tope de 10 en 15 minutos (DEC-32), y así no se
        // le gasta a la Dra. Rivera, que usan las demás pruebas.
        WebDriver solis = navegador();
        entrarComoProfesional(solis, SOLIS_IDONEIDAD);
        ir(solis, "/pro/canjear");
        // Con 1 y 0, fuera del alfabeto: no puede existir.
        escribir(solis, "campo-codigo", "AB1-0CD");
        solis.findElement(testid("boton-canjear")).click();

        visible(solis, "alerta-codigo-invalido");
        assertTrue(ruta(solis).startsWith("/pro/canjear"));
        assertTrue(solis.findElements(testidQueEmpieza("grupo-")).isEmpty());
    }
}
