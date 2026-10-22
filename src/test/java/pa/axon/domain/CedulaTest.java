package pa.axon.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** DEC-24: todas las variantes de la cédula panameña, y nada que se le parezca sin serlo. */
class CedulaTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "8-912-2043",      // Panamá
            "1-1234-123456",   // tomo y asiento al máximo
            "10-12-345",       // comarca Guna Yala
            "13-1-1",          // provincia 13
            "E-8-123456",      // extranjero residente
            "N-19-1234",       // naturalizado
            "PE-8-1234",       // panameño nacido en el extranjero
            "8AV-12-345",      // registro anterior a la ley vigente
            "1PI-12-345",      // población indígena
            "12PI-1-12",       // comarca con sufijo
            "10AV-123-4567"
    })
    void aceptaLasVariantesPanamenas(String cedula) {
        assertTrue(cedula.matches(Cedula.PATTERN), cedula);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0-912-2043",      // no hay provincia 0
            "14-912-2043",     // ni 14
            "8912-2043",       // sin guion
            "8-12345-2043",    // tomo de 5 dígitos
            "8-912-1234567",   // asiento de 7 dígitos
            "8--2043",
            "X-8-1234",        // prefijo inventado
            "EPI-8-1234",      // los sufijos no van con E, N ni PE
            "PEAV-8-1234",
            "8PE-12-345",      // PE es prefijo, no sufijo
            "8 - 912 - 2043",
            "pe-8-1234"        // el patrón compara contra la cédula ya normalizada
    })
    void rechazaLoQueNoEsCedula(String cedula) {
        assertFalse(cedula.matches(Cedula.PATTERN), cedula);
    }

    @Test
    void normalizarRecortaYPasaAMayusculas() {
        assertEquals("PE-8-1234", Cedula.normalizar("  pe-8-1234 "));
        assertEquals("8AV-12-345", Cedula.normalizar("8av-12-345"));
        assertNull(Cedula.normalizar(null));
    }
}
