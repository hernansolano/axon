package pa.axon;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.CartillaService;
import pa.axon.domain.CartillaService.Correccion;
import pa.axon.domain.CartillaService.DetalleVista;
import pa.axon.domain.Category;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.DocumentUpload;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.PermissionService;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;

/**
 * H8 (HU-05): corregir categoría, fecha y origen, adjuntar el papel a un registro sin documento
 * (DEC-40) y eliminar con confirmación. Lo ajeno da 404 y no se toca. Capa web con el servicio simulado.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class CorregirEliminarWebTest {

    private static final byte[] PDF = "%PDF-1.7\n%sintetico".getBytes(StandardCharsets.US_ASCII);

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

    DetalleVista lumbar = new DetalleVista(11L, Category.DIAGNOSTICOS, "Dolor lumbar recurrente", null,
            "Mayo 2026", "Sin institución anotada", null, null);
    DetalleVista losartan = new DetalleVista(10L, Category.MEDICAMENTOS, "Losartán 50 mg", null,
            "Enero 2026", "Clínica del Este", UUID.randomUUID(), "PDF");

    @BeforeEach
    void datos() {
        when(cartilla.detalle(1L, 11L)).thenReturn(Optional.of(lumbar));
        when(cartilla.detalle(1L, 10L)).thenReturn(Optional.of(losartan));
        when(cartilla.correccion(1L, 11L)).thenReturn(
                Optional.of(new Correccion(Category.DIAGNOSTICOS, LocalDate.of(2026, 5, 6), null)));
        when(cartilla.correccion(1L, 10L)).thenReturn(
                Optional.of(new Correccion(Category.MEDICAMENTOS, LocalDate.of(2026, 1, 12), "Clínica del Este")));
        when(cartilla.corregir(eq(1L), eq(11L), any(), any())).thenReturn(true);
        when(cartilla.corregir(eq(1L), eq(10L), any(), any())).thenReturn(true);
        when(cartilla.eliminar(1L, 10L)).thenReturn(true);
        when(cartilla.hoy()).thenReturn(LocalDate.of(2026, 10, 26));
    }

    @Test
    void elDetalleLlevaACorregirYAEliminarSinBorrarNada() throws Exception {
        mvc.perform(get("/cartilla/10").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("href=\"/cartilla/10/editar\""),
                        containsString("href=\"/cartilla/10?eliminar=\""),
                        not(containsString("data-testid=\"confirmar-eliminar\"")))));
        verify(cartilla, never()).eliminar(anyLong(), anyLong());
    }

    @Test
    void eliminarPrimeroPideConfirmacionYDiceQueLaActividadQueda() throws Exception {
        // HU-05, criterio 2.
        mvc.perform(get("/cartilla/10").param("eliminar", "").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"confirmar-eliminar\""),
                        containsString("¿Eliminar este registro?"),
                        containsString("Lo que ya quedó en tu actividad no se borra."),
                        containsString("El documento también se borra."),
                        containsString("action=\"/cartilla/10/eliminar\""),
                        containsString("data-testid=\"cancelar-eliminar\""))));
        verify(cartilla, never()).eliminar(anyLong(), anyLong());
    }

    @Test
    void elPostEliminaYVuelveALaCartilla() throws Exception {
        mvc.perform(post("/cartilla/10/eliminar").with(user(ana)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cartilla"));
        verify(cartilla).eliminar(1L, 10L);
    }

    @Test
    void eliminarSinElTokenCsrfNoBorra() throws Exception {
        mvc.perform(post("/cartilla/10/eliminar").with(user(ana)))
                .andExpect(status().isForbidden());
        verify(cartilla, never()).eliminar(anyLong(), anyLong());
    }

    @Test
    void loAjenoDa404AlVerloCorregirloYEliminarlo() throws Exception {
        // HU-05, criterio 3: igual que un registro que no existe (CP-02).
        mvc.perform(get("/cartilla/99/editar").with(user(ana))).andExpect(status().isNotFound());
        mvc.perform(envio(99L).param("categoria", "OTROS").param("fecha", "2026-01-01").with(user(ana)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/cartilla/99/eliminar").with(user(ana)).with(csrf()))
                .andExpect(status().isNotFound());
        verify(cartilla, never()).corregir(anyLong(), eq(99L), any(), any());
    }

    @Test
    void elFormularioTraeLoGuardadoYNoDejaCorregirElTitulo() throws Exception {
        mvc.perform(get("/cartilla/10/editar").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Losartán 50 mg"),
                        containsString("value=\"2026-01-12\""),
                        // Thymeleaf escapa la tilde en los atributos.
                        containsString("value=\"Cl&iacute;nica del Este\""),
                        containsString("max=\"2026-10-26\""),
                        not(containsString("data-testid=\"campo-titulo\"")),
                        // Ya tiene documento: no se ofrece otro.
                        not(containsString("data-testid=\"campo-foto\"")),
                        containsString(">Guardar la corrección</button>"))));
    }

    @Test
    void sinDocumentoElFormularioOfreceFotografiarElPapel() throws Exception {
        mvc.perform(get("/cartilla/11/editar").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Fotografiar el papel"),
                        containsString("accept=\"image/*\" capture=\"environment\""),
                        containsString("enctype=\"multipart/form-data\""))));
    }

    @Test
    void corregirGuardaYVuelveAlDetalle() throws Exception {
        mvc.perform(envio(11L).param("categoria", "OTROS").param("fecha", "2026-04-30").param("institucion", " Minsa ")
                        .with(user(ana)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cartilla/11"));
        ArgumentCaptor<Correccion> c = ArgumentCaptor.forClass(Correccion.class);
        verify(cartilla).corregir(eq(1L), eq(11L), c.capture(), isNull());
        assertEquals(new Correccion(Category.OTROS, LocalDate.of(2026, 4, 30), "Minsa"), c.getValue());
    }

    @Test
    void adjuntarElPapelAlCorregirMandaElDocumento() throws Exception {
        // DEC-40.
        mvc.perform(envio(11L).file(new MockMultipartFile("archivo", "lumbar.pdf", "application/pdf", PDF))
                        .param("categoria", "DIAGNOSTICOS").param("fecha", "2026-05-06").with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla/11"));
        ArgumentCaptor<DocumentUpload> doc = ArgumentCaptor.forClass(DocumentUpload.class);
        verify(cartilla).corregir(eq(1L), eq(11L), any(), doc.capture());
        assertEquals("application/pdf", doc.getValue().mimeType());
    }

    @Test
    void unRegistroConDocumentoIgnoraElArchivoQueLlegue() throws Exception {
        mvc.perform(envio(10L).file(new MockMultipartFile("archivo", "otra.pdf", "application/pdf", PDF))
                        .param("categoria", "MEDICAMENTOS").param("fecha", "2026-01-12").with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla/10"));
        ArgumentCaptor<DocumentUpload> doc = ArgumentCaptor.forClass(DocumentUpload.class);
        verify(cartilla).corregir(eq(1L), eq(10L), any(), doc.capture());
        assertNull(doc.getValue());
    }

    @Test
    void loQueFaltaSeNombraYNoSeGuarda() throws Exception {
        mvc.perform(envio(11L).with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Para guardarlo nos faltan estos datos:"),
                        containsString("data-testid=\"falta-categoria\""),
                        containsString("data-testid=\"falta-fecha\""),
                        not(containsString("data-testid=\"falta-titulo\"")))));
        verify(cartilla, never()).corregir(anyLong(), anyLong(), any(), any());
    }

    @Test
    void unaFechaFuturaTieneSuPropioMensajeAlCorregirYAlAgregar() throws Exception {
        // DEC-39.
        when(cartilla.esFutura(LocalDate.of(2026, 12, 1))).thenReturn(true);
        mvc.perform(envio(11L).param("categoria", "OTROS").param("fecha", "2026-12-01").with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("La fecha no puede ser posterior a hoy."),
                        not(containsString("data-testid=\"falta-fecha\"")))));
        mvc.perform(multipart("/cartilla/nuevo").param("titulo", "Influenza").param("categoria", "VACUNAS")
                        .param("fecha", "2026-12-01").with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("La fecha no puede ser posterior a hoy.")));
        verify(cartilla, never()).corregir(anyLong(), anyLong(), any(), any());
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    private static MockMultipartHttpServletRequestBuilder envio(Long id) {
        return multipart("/cartilla/" + id + "/editar");
    }
}
