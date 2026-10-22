package pa.axon;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.CartillaService;
import pa.axon.domain.CartillaService.CartillaVista;
import pa.axon.domain.CartillaService.GrupoVista;
import pa.axon.domain.CartillaService.RegistroVista;
import pa.axon.domain.Category;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.Patient;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.PermissionService;
import pa.axon.domain.Professional;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AccountType;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;

/**
 * H1: las dos puertas y la regla que gobierna todo. Capa web con repositorios simulados: no
 * necesita la base, así que corre en cualquier máquina y en el CI.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class AccessControlTest {

    private static final String CLAVE = "clave-de-prueba";

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder encoder;

    @MockitoBean
    PatientRepository patients;

    @MockitoBean
    ProfessionalRepository professionals;

    @MockitoBean
    CartillaService cartilla;

    @MockitoBean
    PermissionService permisos;

    @MockitoBean
    ConsultaService consultas;

    @MockitoBean
    LoginAttemptService attempts;

    @MockitoBean
    PatientRegistrationService registration;

    @MockitoBean
    ActivityService actividad;

    AxonUser ana = AxonUser.patient(1L, "8-912-2043", "Ana Cedeño", "x");
    AxonUser rivera = AxonUser.professional(1L, "MED-4471", "Dra. Rivera", "Clínica del Este", "x");

    @BeforeEach
    void datos() {
        String hash = encoder.encode(CLAVE);
        when(patients.findByCedula("8-912-2043")).thenReturn(Optional.of(new Patient("8-912-2043", "Ana Cedeño", hash)));
        when(professionals.findByIdoneidad("MED-4471"))
                .thenReturn(Optional.of(new Professional("MED-4471", "Dra. Rivera", "Clínica del Este", hash)));

        var dicho = new RegistroVista(1L, "Dolor lumbar recurrente", "", "mayo 2026 · Sin institución anotada", false, null);
        var conDoc = new RegistroVista(2L, "Losartán 50 mg", "", "enero 2026 · Clínica del Este", true, "PDF");
        when(cartilla.deLaPaciente(1L)).thenReturn(new CartillaVista(2, "2 registros", List.of(
                new GrupoVista(Category.DIAGNOSTICOS, "1 registro", List.of(dicho)),
                new GrupoVista(Category.MEDICAMENTOS, "1 registro", List.of(conDoc)))));
    }

    @Test
    void saludResponde200ConLaVersion() throws Exception {
        mvc.perform(get("/salud")).andExpect(status().isOk()).andExpect(content().string("AXON 0.1.0"));
    }

    @Test
    void sinSesionLaCartillaRedirigeAlLoginYNoFiltraContenido() throws Exception {
        mvc.perform(get("/cartilla"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/"));
    }

    @Test
    void unProfesionalAutenticadoNoEntraALaCartilla() throws Exception {
        mvc.perform(get("/cartilla").with(user(rivera)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("Losartán"))));
    }

    @Test
    void unPacienteAutenticadoNoEntraAlCanje() throws Exception {
        mvc.perform(get("/pro/canjear").with(user(ana))).andExpect(status().isForbidden());
    }

    @Test
    void sinSesionElCanjeRedirigeAlLoginProfesional() throws Exception {
        mvc.perform(get("/pro/canjear"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/pro"));
    }

    @Test
    void noExisteNingunaRutaQueBusqueOListePacientes() throws Exception {
        mvc.perform(get("/pro/pacientes").with(user(rivera))).andExpect(status().isNotFound());
        mvc.perform(get("/pacientes").with(user(ana))).andExpect(status().isForbidden());
    }

    @Test
    void credencialesMalasDanElMismoAvisoSeaCualSeaElDatoFallado() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("cedula", "0-000-0000").param("clave", CLAVE))
                .andExpect(redirectedUrl("/?error"));
        mvc.perform(post("/login").with(csrf()).param("cedula", "8-912-2043").param("clave", "incorrecta"))
                .andExpect(redirectedUrl("/?error"));
    }

    @Test
    void cadaIntentoFallidoQuedaRegistradoConLaPuertaQueSeUso() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("cedula", "8-912-2043").param("clave", "incorrecta"));
        mvc.perform(post("/pro/login").with(csrf()).param("idoneidad", "MED-0000").param("clave", CLAVE));
        verify(attempts).record(eq(AccountType.PACIENTE), eq("8-912-2043"), any(), eq("BadCredentialsException"));
        verify(attempts).record(eq(AccountType.PROFESIONAL), eq("MED-0000"), any(), eq("BadCredentialsException"));
    }

    @Test
    void unLoginCorrectoNoRegistraIntentoFallido() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("cedula", "8-912-2043").param("clave", CLAVE));
        verifyNoInteractions(attempts);
    }

    @Test
    void elLoginProfesionalOfreceRegistrarseYElAvisoDiceQueEstaFueraDelAlcance() throws Exception {
        mvc.perform(get("/pro"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"boton-registrarse\"")))
                .andExpect(content().string(containsString("data-testid=\"dialogo-fuera-de-alcance\"")))
                .andExpect(content().string(containsString("El registro está fuera del alcance")))
                .andExpect(content().string(containsString("verificación de la idoneidad")));
    }

    @Test
    void elLoginDelPacienteLlevaAlRegistroSinAvisoDeFueraDeAlcance() throws Exception {
        // DEC-16 reemplaza a DEC-12 para la paciente.
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/registro\" data-testid=\"boton-registrarse\"")))
                .andExpect(content().string(not(containsString("dialogo-fuera-de-alcance"))));
    }

    @Test
    void elAvisoDeRegistroNoVaDentroDelFormularioDeLogin() throws Exception {
        // HTML no admite formularios anidados: dentro del login, el navegador descartaría el
        // <form method="dialog"> y "Entendido" enviaría el login.
        String html = mvc.perform(get("/pro")).andReturn().getResponse().getContentAsString();
        int cierreDelLogin = html.indexOf("</form>");
        org.junit.jupiter.api.Assertions.assertTrue(cierreDelLogin > 0 && cierreDelLogin < html.indexOf("<dialog"),
                "el <dialog> debe ir después de cerrar el formulario de login del profesional");
    }

    @Test
    void elScriptDelAvisoSeSirveSinSesion() throws Exception {
        mvc.perform(get("/js/axon.js")).andExpect(status().isOk());
    }

    @Test
    void laPantallaDeLoginNoDiceCualDeLosDosDatosFallo() throws Exception {
        mvc.perform(get("/").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("La cédula o la clave no coinciden. Revisa los dos datos.")));
    }

    @Test
    void laPacienteEntraPorSuPuertaYAterrizaEnSuCartilla() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("cedula", "8-912-2043").param("clave", CLAVE))
                .andExpect(redirectedUrl("/cartilla"));
    }

    @Test
    void laClaveDeUnaPacienteNoAbreLaPuertaDelProfesional() throws Exception {
        mvc.perform(post("/pro/login").with(csrf()).param("idoneidad", "8-912-2043").param("clave", CLAVE))
                .andExpect(redirectedUrl("/pro?error"));
    }

    @Test
    void laProfesionalEntraPorSuPuertaYAterrizaEnElCanje() throws Exception {
        mvc.perform(post("/pro/login").with(csrf()).param("idoneidad", "MED-4471").param("clave", CLAVE))
                .andExpect(redirectedUrl("/pro/canjear"));
    }

    @Test
    void laCartillaSeMuestraAgrupadaYConLaProcedenciaCorrecta() throws Exception {
        mvc.perform(get("/cartilla").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hola, Ana")))
                .andExpect(content().string(containsString("data-testid=\"grupo-diagnosticos\"")))
                .andExpect(content().string(containsString("data-testid=\"grupo-medicamentos\"")))
                .andExpect(content().string(containsString("data-procedencia=\"autodeclarado\"")))
                .andExpect(content().string(containsString("data-procedencia=\"con-documento\"")));
    }

    @Test
    void laVistaNuncaRecibeElHashDeLaClave() throws Exception {
        mvc.perform(get("/cartilla").with(user(ana)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.model()
                        .attribute("usuario", org.hamcrest.Matchers.not(org.hamcrest.Matchers.instanceOf(AxonUser.class))));
    }

    @Test
    void laNavegacionDelPacienteTieneSusCincoDestinos() throws Exception {
        for (String destino : List.of("/cartilla", "/compartir", "/permisos", "/emergencia", "/actividad")) {
            mvc.perform(get(destino).with(user(ana)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("data-testid=\"nav-cartilla\"")))
                    .andExpect(content().string(containsString("data-testid=\"nav-compartir\"")))
                    .andExpect(content().string(containsString("data-testid=\"nav-permisos\"")))
                    .andExpect(content().string(containsString("data-testid=\"nav-emergencia\"")))
                    .andExpect(content().string(containsString("data-testid=\"nav-actividad\"")));
        }
    }

    @Test
    void lasFuentesSeSirvenSinSesionYNingunaPaginaLasPideAUnTercero() throws Exception {
        // DEC-10: el login también las usa, así que no pueden exigir sesión; y nada sale a Google Fonts.
        mvc.perform(get("/css/fuentes.css")).andExpect(status().isOk());
        mvc.perform(get("/fonts/figtree/_Xms-HUzqDCFdgfMm4S9DQ.woff2")).andExpect(status().isOk());
        for (String pagina : List.of("/", "/pro")) {
            mvc.perform(get(pagina))
                    .andExpect(content().string(containsString("/css/fuentes.css")))
                    .andExpect(content().string(not(containsString("fonts.googleapis.com"))));
        }
    }

    @Test
    void elProfesionalNoTieneBarraDeNavegacion() throws Exception {
        mvc.perform(get("/pro/canjear").with(user(rivera)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dra. Rivera · MED-4471")))
                .andExpect(content().string(not(containsString("data-testid=\"nav-"))));
    }
}
