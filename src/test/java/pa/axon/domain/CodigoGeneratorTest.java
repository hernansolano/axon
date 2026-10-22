package pa.axon.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** CP-16 (HU-07, criterio 5): el código usa solo el alfabeto de ESPEC §4. */
class CodigoGeneratorTest {

    private static final Pattern FORMATO = Pattern.compile("[ABCDEFGHJKLMNPQRSTUVWXYZ234679]{3}-[ABCDEFGHJKLMNPQRSTUVWXYZ234679]{3}");

    @Test
    void enDiezMilCodigosNoApareceNingunaIONiCeroUnoCincoOcho() {
        CodigoGenerator generator = new CodigoGenerator();
        Set<Character> vistos = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = generator.next();
            assertTrue(FORMATO.matcher(code).matches(), code);
            for (char prohibido : "IO0158".toCharArray()) {
                assertTrue(code.indexOf(prohibido) < 0, code);
            }
            code.chars().filter(c -> c != '-').forEach(c -> vistos.add((char) c));
        }
        // Sesenta mil caracteres alcanzan para ver los treinta símbolos: ninguno quedó fuera por error.
        assertEquals(30, vistos.size());
    }

    @Test
    void elAlfabetoEsElDeLaEspecificacion() {
        assertEquals("ABCDEFGHJKLMNPQRSTUVWXYZ234679", CodigoGenerator.ALFABETO);
        assertEquals(30, CodigoGenerator.ALFABETO.chars().distinct().count());
    }

    @Test
    void usaElAzarQueRecibe() {
        // El primero y el último del alfabeto, para ver que el índice cubre los dos extremos.
        CodigoGenerator generator = new CodigoGenerator(new Random(0) {
            private int n;

            @Override
            public int nextInt(int bound) {
                return n++ % 2 == 0 ? 0 : bound - 1;
            }
        });
        assertEquals("A9A-9A9", generator.next());
    }
}
