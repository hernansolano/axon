package pa.axon.domain;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Códigos de autorización de ESPEC §4: seis caracteres con guion después del tercero, del alfabeto
 * que se dicta en voz alta sin confundirse (sin I, O, 0, 1, 5 ni 8). Con {@link SecureRandom}, porque
 * el código es lo único que separa a un profesional de la cartilla.
 */
@Component
public class CodigoGenerator {

    public static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ234679";

    private final RandomGenerator random;

    public CodigoGenerator() {
        this(new SecureRandom());
    }

    CodigoGenerator(RandomGenerator random) {
        this.random = random;
    }

    public String next() {
        StringBuilder code = new StringBuilder(7);
        for (int i = 0; i < 6; i++) {
            if (i == 3) {
                code.append('-');
            }
            code.append(ALFABETO.charAt(random.nextInt(ALFABETO.length())));
        }
        return code.toString();
    }
}
