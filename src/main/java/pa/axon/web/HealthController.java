package pa.axon.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final String version;

    public HealthController(@Value("${axon.version}") String version) {
        this.version = version;
    }

    @GetMapping(value = "/salud", produces = MediaType.TEXT_PLAIN_VALUE)
    public String salud() {
        return "AXON " + version;
    }
}
