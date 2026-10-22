package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/**
 * CP-17 (HU-13): la paciente crea su cuenta, vuelve al login con el aviso, entra con su clave y su
 * cartilla está vacía. Los datos que faltan se nombran. Cada corrida usa una cédula sintética nueva.
 */
class Cp17RegistroPacienteE2E extends E2EBase {

    private static final SecureRandom AZAR = new SecureRandom();

    @Test
    void creaLaCuentaVaAlLoginConAvisoYEntraASuCartillaVacia() {
        // Provincia 9 (Veraguas) y un número que el sembrado no usa.
        String cedula = "9-" + (100 + AZAR.nextInt(900)) + "-" + (1000 + AZAR.nextInt(9000));
        String clave = "clave-e2e-" + AZAR.nextInt(1_000_000);

        WebDriver nueva = navegador();
        ir(nueva, "/");
        visible(nueva, "boton-registrarse").click();
        escribir(nueva, "campo-cedula", cedula);
        escribir(nueva, "campo-nombre", "Prueba Sintética");
        escribir(nueva, "campo-clave", clave);
        escribir(nueva, "campo-clave-confirmar", clave);
        nueva.findElement(testid("boton-crear-cuenta")).click();

        visible(nueva, "aviso-cuenta-creada");
        assertEquals("/", ruta(nueva));
        sinElemento(nueva, "saludo");

        entrarComoPaciente(nueva, cedula, clave);
        assertTrue(visible(nueva, "saludo").getText().contains("Prueba"));
        visible(nueva, "cartilla-vacia");
        assertTrue(nueva.findElements(testidQueEmpieza("registro-")).isEmpty());
    }

    @Test
    void losDatosQueFaltanSeNombranUnoPorUno() {
        WebDriver nueva = navegador();
        ir(nueva, "/registro");
        // Sin la ayuda del navegador, para que conteste el servidor (RS-12).
        ((JavascriptExecutor) nueva).executeScript("document.querySelectorAll('form').forEach(f => f.noValidate = true);");
        nueva.findElement(testid("boton-crear-cuenta")).click();

        String alerta = visible(nueva, "alerta-registro").getText();
        for (String falta : new String[] {"Falta tu cédula.", "Falta tu nombre.", "Falta tu clave.", "Falta repetir tu clave."}) {
            assertTrue(alerta.contains(falta), "No nombra: " + falta);
        }
        sinElemento(nueva, "aviso-cuenta-creada");
    }

    @Test
    void lasClavesQueNoCoincidenSeDicen() {
        WebDriver nueva = navegador();
        ir(nueva, "/registro");
        escribir(nueva, "campo-cedula", "9-" + (100 + AZAR.nextInt(900)) + "-" + (1000 + AZAR.nextInt(9000)));
        escribir(nueva, "campo-nombre", "Prueba Sintética");
        escribir(nueva, "campo-clave", "clave-uno-123");
        escribir(nueva, "campo-clave-confirmar", "clave-dos-456");
        nueva.findElement(testid("boton-crear-cuenta")).click();

        assertTrue(visible(nueva, "alerta-registro").getText().contains("Las dos claves no coinciden."));
    }
}
