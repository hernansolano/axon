package pa.axon;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.CartillaService;
import pa.axon.domain.CartillaService.ArchivoVista;
import pa.axon.domain.CartillaService.CartillaVista;
import pa.axon.domain.CartillaService.DetalleVista;
import pa.axon.domain.CartillaService.GrupoVista;
import pa.axon.domain.CartillaService.RegistroVista;
import pa.axon.domain.Category;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.PermissionService;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;

/**
 * H2: el detalle del registro, el visor y /documento/{id}. Lo ajeno, lo inexistente y lo mal
 * formado responden 404, nunca 403 (CP-02, CP-15). Capa web con el servicio simulado.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class CartillaWebTest {

    @Autowired
    MockMvc mvc;

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
    UUID receta = UUID.fromString("3f2b8c1e-5a4d-4e6f-9b7a-0c1d2e3f4a5b");

    @BeforeEach
    void datos() {
        when(cartilla.detalle(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(cartilla.documento(anyLong(), any())).thenReturn(Optional.empty());
        when(cartilla.detalle(1L, 2L)).thenReturn(Optional.of(new DetalleVista(2L, Category.MEDICAMENTOS,
                "Losartán 50 mg", "1 tableta diaria, en la mañana", "Enero 2026", "Clínica del Este", receta, "PDF")));
        when(cartilla.detalle(1L, 3L)).thenReturn(Optional.of(new DetalleVista(3L, Category.DIAGNOSTICOS,
                "Dolor lumbar recurrente", null, "Mayo 2026", "Sin institución anotada", null, null)));
        when(cartilla.documento(1L, receta)).thenReturn(Optional.of(new ArchivoVista("application/pdf", new byte[] {37, 80, 68, 70})));
    }

    @Test
    void cadaRegistroDeLaCartillaLlevaASuDetalle() throws Exception {
        var conDoc = new RegistroVista(2L, "Losartán 50 mg", "", "enero 2026 · Clínica del Este", true, "PDF");
        when(cartilla.deLaPaciente(1L)).thenReturn(new CartillaVista(1, "1 registro",
                List.of(new GrupoVista(Category.MEDICAMENTOS, "1 registro", List.of(conDoc)))));
        mvc.perform(get("/cartilla").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/cartilla/2\"")));
    }

    @Test
    void unRegistroAjenoOQueNoExisteDa404YNo403() throws Exception {
        // CP-02: el servicio no distingue "de otra" de "no existe"; el controlador tampoco.
        mvc.perform(get("/cartilla/99").with(user(ana)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Losartán"))));
    }

    @Test
    void unIdQueNoEsNumeroTambienDa404() throws Exception {
        mvc.perform(get("/cartilla/abc").with(user(ana))).andExpect(status().isNotFound());
        mvc.perform(get("/cartilla/99999999999999999999999").with(user(ana))).andExpect(status().isNotFound());
    }

    @Test
    void sinSesionElDetalleRedirigeAlLogin() throws Exception {
        mvc.perform(get("/cartilla/2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/"));
    }

    @Test
    void unProfesionalNoAbreElDetalleNiElDocumento() throws Exception {
        mvc.perform(get("/cartilla/2").with(user(rivera)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("Losartán"))));
        mvc.perform(get("/documento/" + receta).with(user(rivera))).andExpect(status().isForbidden());
    }

    @Test
    void elDetalleConDocumentoTraeElVisorConSusEstadosYLosDatosDelRegistro() throws Exception {
        mvc.perform(get("/cartilla/2").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"visor-documento\"")))
                .andExpect(content().string(containsString("data-src=\"/documento/" + receta + "\"")))
                .andExpect(content().string(containsString("data-tipo=\"pdf\"")))
                .andExpect(content().string(containsString("data-testid=\"visor-cargando\"")))
                .andExpect(content().string(containsString("El documento no cargó")))
                .andExpect(content().string(containsString("data-testid=\"documento-reintentar\"")))
                .andExpect(content().string(containsString("Volver a intentar")))
                // HU-04 §7: los datos del registro están en la página aunque el documento falle.
                .andExpect(content().string(containsString("Losartán 50 mg")))
                .andExpect(content().string(containsString("Clínica del Este")))
                .andExpect(content().string(containsString("data-procedencia=\"con-documento\"")))
                .andExpect(content().string(containsString("Con documento · PDF")))
                .andExpect(content().string(containsString("No tiene un enlace propio que se pueda reenviar")))
                .andExpect(content().string(not(containsString("data-testid=\"visor-sin-documento\""))));
    }

    @Test
    void elDetalleSinDocumentoLoMarcaComoAutodeclarado() throws Exception {
        // CP-05, con un registro sembrado sin documento (la creación llega con H7).
        mvc.perform(get("/cartilla/3").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"visor-sin-documento\"")))
                .andExpect(content().string(containsString("Sin documento")))
                .andExpect(content().string(containsString("Lo escribiste tú, sin adjuntar el papel.")))
                .andExpect(content().string(containsString("data-procedencia=\"autodeclarado\"")))
                .andExpect(content().string(containsString("Lo dijo la paciente, sin documento")))
                .andExpect(content().string(containsString("Sin institución anotada")))
                .andExpect(content().string(not(containsString("data-testid=\"visor-documento\""))))
                .andExpect(content().string(not(containsString("/documento/"))));
    }

    @Test
    void laDuenaRecibeElDocumentoEnLineaYSinCache() throws Exception {
        mvc.perform(get("/documento/" + receta).with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    @Test
    void unDocumentoDeUnTipoQueElNavegadorEjecutariaNoSeSirve() throws Exception {
        // Guardián de acceso, H2: cuando H7 permita subir archivos, un HTML o un SVG no puede salir de aquí.
        UUID pagina = UUID.randomUUID();
        UUID dibujo = UUID.randomUUID();
        when(cartilla.documento(1L, pagina)).thenReturn(Optional.of(new ArchivoVista("text/html", "<script>".getBytes())));
        when(cartilla.documento(1L, dibujo)).thenReturn(Optional.of(new ArchivoVista("image/svg+xml", "<svg/>".getBytes())));
        mvc.perform(get("/documento/" + pagina).with(user(ana))).andExpect(status().isNotFound());
        mvc.perform(get("/documento/" + dibujo).with(user(ana))).andExpect(status().isNotFound());
    }

    @Test
    void unDocumentoAdivinadoDa404() throws Exception {
        // CP-15: un UUID bien formado que no es de ningún documento de Ana, o que ya no está en disco.
        mvc.perform(get("/documento/" + UUID.randomUUID()).with(user(ana))).andExpect(status().isNotFound());
    }

    @Test
    void unaDireccionDeDocumentoMalFormadaDa404() throws Exception {
        mvc.perform(get("/documento/1").with(user(ana))).andExpect(status().isNotFound());
        mvc.perform(get("/documento/receta-losartan.pdf").with(user(ana))).andExpect(status().isNotFound());
    }

    @Test
    void sinSesionElDocumentoRedirigeAlLogin() throws Exception {
        mvc.perform(get("/documento/" + receta))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/"));
    }
}
