package pa.axon.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La única fuente de la hora (DEC-28). Crear, vencer y revocar un permiso la piden a este reloj, y
 * las pruebas lo reemplazan por uno fijo para comprobar el vencimiento sin esperar.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId PANAMA = ZoneId.of("America/Panama");

    @Bean
    public Clock clock() {
        return Clock.system(PANAMA);
    }
}
