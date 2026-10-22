package pa.axon.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Base de la suite de extremo a extremo (H6, PB-15). Corre contra una app ya levantada con el perfil
 * dev y los datos sintéticos de ESPEC §8; la dirección sale de {@code axon.e2e.url}. Cada prueba abre
 * sus navegadores y los cierra al terminar; si falla, deja una captura de cada uno en
 * {@code target/failsafe-reports/capturas}. Todo se busca por {@code data-testid}.
 */
@ExtendWith(E2EBase.CapturaAlFallar.class)
abstract class E2EBase {

    static final String URL = System.getProperty("axon.e2e.url", "http://localhost:8080").replaceAll("/$", "");
    static final boolean HEADLESS = Boolean.parseBoolean(System.getProperty("axon.e2e.headless", "true"));

    /** Datos sintéticos de ESPEC §8; la clave es la del sembrado de application-dev.yml. */
    static final String ANA_CEDULA = "8-912-2043";
    static final String RIVERA_IDONEIDAD = "MED-4471";
    static final String SOLIS_IDONEIDAD = "MED-2210";
    static final String CLAVE = System.getProperty("axon.e2e.clave", "axon1234");

    /** El alfabeto de ESPEC §4: sin I, O, 0, 1, 5 ni 8. */
    static final String CODIGO = "[A-HJ-NP-Z2-46-79]{3}-[A-HJ-NP-Z2-46-79]{3}";

    private final List<WebDriver> navegadores = new ArrayList<>();

    WebDriver navegador() {
        ChromeOptions opciones = new ChromeOptions();
        if (HEADLESS) {
            opciones.addArguments("--headless=new");
        }
        opciones.addArguments("--window-size=1280,900", "--no-sandbox", "--disable-dev-shm-usage", "--lang=es");
        WebDriver driver = new ChromeDriver(opciones);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        navegadores.add(driver);
        return driver;
    }

    @AfterEach
    void cerrarNavegadores() {
        navegadores.forEach(WebDriver::quit);
        navegadores.clear();
    }

    static By testid(String id) {
        return By.cssSelector("[data-testid='" + id + "']");
    }

    static By testidQueEmpieza(String prefijo) {
        return By.cssSelector("[data-testid^='" + prefijo + "']");
    }

    static WebDriverWait espera(WebDriver driver, int segundos) {
        return new WebDriverWait(driver, Duration.ofSeconds(segundos));
    }

    static WebElement visible(WebDriver driver, String id) {
        return espera(driver, 10).until(ExpectedConditions.visibilityOfElementLocated(testid(id)));
    }

    static void ir(WebDriver driver, String ruta) {
        driver.get(URL + ruta);
    }

    static String ruta(WebDriver driver) {
        return URI.create(driver.getCurrentUrl()).getPath();
    }

    static void escribir(WebDriver driver, String id, String texto) {
        WebElement campo = visible(driver, id);
        campo.clear();
        campo.sendKeys(texto);
    }

    /** Entra como paciente y espera la cartilla. */
    static void entrarComoPaciente(WebDriver driver, String cedula, String clave) {
        ir(driver, "/");
        escribir(driver, "campo-cedula", cedula);
        escribir(driver, "campo-clave", clave);
        driver.findElement(testid("boton-entrar")).click();
        espera(driver, 10).until(ExpectedConditions.urlContains("/cartilla"));
    }

    /** Entra como profesional y espera la pantalla de canje. */
    static void entrarComoProfesional(WebDriver driver, String idoneidad) {
        ir(driver, "/pro");
        escribir(driver, "campo-idoneidad", idoneidad);
        escribir(driver, "campo-clave", CLAVE);
        driver.findElement(testid("boton-entrar")).click();
        espera(driver, 10).until(ExpectedConditions.urlContains("/pro/canjear"));
    }

    /** Un permiso recién creado: su id (de la dirección) y el código que se le dicta al profesional. */
    record Permiso(long id, String codigo) {
    }

    /** La paciente comparte esas categorías por esa vigencia (15, 120 o 1440 minutos) y lee el código. */
    static Permiso compartir(WebDriver driver, int minutos, String... categorias) {
        ir(driver, "/compartir");
        for (String c : categorias) {
            visible(driver, "categoria-" + c).click();
        }
        driver.findElement(testid("vigencia-" + minutos)).click();
        driver.findElement(testid("boton-generar-codigo")).click();
        WebElement codigo = visible(driver, "codigo-generado");
        String path = ruta(driver);
        long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        return new Permiso(id, codigo.getAttribute("data-codigo"));
    }

    /** El profesional canjea el código y queda en la consulta. */
    static void canjear(WebDriver driver, String codigo) {
        ir(driver, "/pro/canjear");
        escribir(driver, "campo-codigo", codigo);
        driver.findElement(testid("boton-canjear")).click();
        espera(driver, 10).until(ExpectedConditions.urlMatches(".*/pro/consulta/\\d+$"));
    }

    /**
     * El HTML tal como lo manda el servidor, con la sesión de ese navegador (CP-09): lo que llega oculto
     * con CSS o se quita con JavaScript sí aparece aquí.
     */
    static HttpResponse<String> crudo(WebDriver driver, String ruta) {
        String cookies = driver.manage().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(Collectors.joining("; "));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(URL + ruta)).header("Cookie", cookies).GET().build();
        try {
            return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                    .send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo pedir " + ruta, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static void sinElemento(WebDriver driver, String id) {
        assertTrue(driver.findElements(testid(id)).isEmpty(), "No debía estar " + id);
    }

    /** Si la prueba falló, una captura de cada navegador antes de cerrarlos (HERRAMIENTAS.md, evidencia). */
    static class CapturaAlFallar implements AfterTestExecutionCallback {

        @Override
        public void afterTestExecution(ExtensionContext context) {
            Optional<Object> instancia = context.getTestInstance();
            if (context.getExecutionException().isEmpty() || instancia.isEmpty() || !(instancia.get() instanceof E2EBase base)) {
                return;
            }
            Path carpeta = Path.of("target", "failsafe-reports", "capturas");
            String nombre = context.getRequiredTestClass().getSimpleName() + "-" + context.getRequiredTestMethod().getName();
            for (int i = 0; i < base.navegadores.size(); i++) {
                try {
                    Files.createDirectories(carpeta);
                    Path png = ((TakesScreenshot) base.navegadores.get(i)).getScreenshotAs(OutputType.FILE).toPath();
                    Files.copy(png, carpeta.resolve(nombre + "-" + (i + 1) + ".png"), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    // Sin captura no se pierde el resultado de la prueba.
                }
            }
        }
    }
}
