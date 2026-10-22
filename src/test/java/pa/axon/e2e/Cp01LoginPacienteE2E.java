package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

/** CP-01 (HU-01): la paciente entra con credenciales válidas; con inválidas se queda fuera y se le dice. */
class Cp01LoginPacienteE2E extends E2EBase {

    @Test
    void conCedulaYClaveValidasEntraASuCartilla() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);

        assertEquals("/cartilla", ruta(ana));
        assertTrue(visible(ana, "saludo").getText().contains("Ana"));
        assertTrue(visible(ana, "usuario-sesion").getText().contains(ANA_CEDULA));
    }

    @Test
    void conClaveEquivocadaSeQuedaEnElLoginConElAviso() {
        WebDriver ana = navegador();
        ir(ana, "/");
        escribir(ana, "campo-cedula", ANA_CEDULA);
        escribir(ana, "campo-clave", "no-es-la-clave");
        ana.findElement(testid("boton-entrar")).click();

        visible(ana, "alerta-credenciales");
        sinElemento(ana, "saludo");
        // Sin sesión, la cartilla manda de vuelta al login.
        ir(ana, "/cartilla");
        visible(ana, "campo-cedula");
        sinElemento(ana, "saludo");
    }

    @Test
    void conUnaCedulaQueNoExisteTambienSeQuedaFuera() {
        WebDriver nadie = navegador();
        ir(nadie, "/");
        escribir(nadie, "campo-cedula", "4-123-4567");
        escribir(nadie, "campo-clave", CLAVE);
        nadie.findElement(testid("boton-entrar")).click();

        visible(nadie, "alerta-credenciales");
        sinElemento(nadie, "saludo");
    }

    @Test
    void salirCierraLaSesion() {
        WebDriver ana = navegador();
        entrarComoPaciente(ana, ANA_CEDULA, CLAVE);
        visible(ana, "boton-salir").click();

        visible(ana, "campo-cedula");
        ir(ana, "/cartilla");
        visible(ana, "campo-cedula");
    }
}
