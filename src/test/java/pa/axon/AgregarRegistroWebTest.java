package pa.axon;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import pa.axon.domain.CartillaService.CartillaVista;
import pa.axon.domain.CartillaService.NuevoRegistro;
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
 * H7 (HU-03): agregar un registro. Lo que falta se nombra y se lista en el orden de los campos
 * (UI.md 07b), el documento se acepta por su contenido y el registro es siempre de la paciente de la
 * sesión. Capa web con el servicio simulado.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class AgregarRegistroWebTest {

    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] PDF = "%PDF-1.7\n%sintetico".getBytes(StandardCharsets.US_ASCII);

    /** Los avisos del navegador repiten los textos de tipo y peso; los del servidor van en la alerta. */
    private static final String PROBLEMA = "data-testid=\"problema-registro\">";

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

    @Test
    void laCartillaLlevaAAgregarUnRegistro() throws Exception {
        when(cartilla.deLaPaciente(1L)).thenReturn(new CartillaVista(0, "0 registros", List.of()));
        mvc.perform(get("/cartilla").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/cartilla/nuevo\"")));
    }

    @Test
    void elFormularioAbreLaCamaraSinGetUserMediaYEmpiezaSinDocumento() throws Exception {
        String html = mvc.perform(get("/cartilla/nuevo").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("enctype=\"multipart/form-data\""),
                        containsString("accept=\"image/*\" capture=\"environment\""),
                        containsString("Tomar la foto del papel"),
                        containsString("O adjunta otro archivo"),
                        containsString("JPG, PNG, WebP o PDF · hasta 10 MB"),
                        containsString("El documento es opcional."),
                        containsString("data-testid=\"categoria-vacunas\""),
                        containsString("data-testid=\"categoria-otros\""),
                        containsString(">Guardar sin documento</button>"),
                        not(containsString("getUserMedia")),
                        not(containsString("alerta-faltan-datos")))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // DEC-27: «otro archivo» acepta galería, archivos o PDF, y no lleva capture.
        var otro = Pattern.compile("<input[^>]*id=\"archivo\"[^>]*>").matcher(html);
        assertTrue(otro.find());
        assertTrue(otro.group().contains("accept=\"image/jpeg,image/png,image/webp,application/pdf\""));
        assertTrue(!otro.group().contains("capture"));
    }

    @Test
    void sinNadaListaLosTresDatosQueFaltanEnElOrdenDeLosCamposYNoGuarda() throws Exception {
        // HU-03 §4 y UI.md 07b: por su nombre, nunca "campos obligatorios".
        mvc.perform(envio().with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"alerta-faltan-datos\""),
                        stringContainsInOrder("Para guardarlo nos faltan estos datos:",
                                "Qué dice el papel: el medicamento, la vacuna o el examen.",
                                "De qué se trata, entre las cinco categorías.",
                                "La fecha que aparece en el papel."),
                        not(containsString("obligatorio")))));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void soloNombraLoQueFaltaYConservaLoQueYaSeEscribio() throws Exception {
        String html = mvc.perform(envio().param("titulo", "Influenza, dosis anual").param("categoria", "VACUNAS")
                        .param("institucion", "Minsa").with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"falta-fecha\""),
                        not(containsString("data-testid=\"falta-titulo\"")),
                        not(containsString("data-testid=\"falta-categoria\"")),
                        containsString("value=\"Influenza, dosis anual\""),
                        containsString("value=\"Minsa\""))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // La categoría elegida sigue marcada, y es la única.
        assertEquals(1, html.split("checked=\"checked\"", -1).length - 1);
        assertTrue(Pattern.compile("<input[^>]*value=\"VACUNAS\"[^>]*checked=\"checked\"").matcher(html).find());
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void unaFechaQueNoEsFechaOUnaCategoriaQueNoExisteCuentanComoFaltantes() throws Exception {
        mvc.perform(envio().param("titulo", "Hemograma").param("categoria", "ADMIN").param("fecha", "2026-13-45")
                        .with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"falta-categoria\""),
                        containsString("data-testid=\"falta-fecha\""),
                        not(containsString("typeMismatch")),
                        not(containsString("Failed to convert")))));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void sinDocumentoSeGuardaParaLaPacienteDeLaSesionYVuelveALaCartilla() throws Exception {
        // RS-02: un paciente_id en la solicitud no cambia de quién es el registro.
        mvc.perform(completo().param("pacienteId", "2").param("patientId", "2").with(user(ana)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/cartilla"));
        ArgumentCaptor<NuevoRegistro> nuevo = ArgumentCaptor.forClass(NuevoRegistro.class);
        verify(cartilla).agregar(eq(1L), nuevo.capture(), isNull());
        assertEquals(new NuevoRegistro(Category.VACUNAS, "Influenza · dosis anual", LocalDate.of(2026, 4, 2), "Minsa"),
                nuevo.getValue());
    }

    @Test
    void conFotoSeGuardaConDocumentoYElTipoSaleDelContenido() throws Exception {
        // HU-03 §1: el navegador dice octet-stream, el contenido dice JPEG.
        mvc.perform(completo().file(new MockMultipartFile("foto", "image.jpg", "application/octet-stream", JPG))
                        .with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla"));
        ArgumentCaptor<DocumentUpload> doc = ArgumentCaptor.forClass(DocumentUpload.class);
        verify(cartilla).agregar(eq(1L), any(), doc.capture());
        assertEquals("image/jpeg", doc.getValue().mimeType());
        assertEquals("image.jpg", doc.getValue().originalName());
    }

    @Test
    void conUnPdfComoOtroArchivoSeGuardaConDocumento() throws Exception {
        mvc.perform(completo().file(new MockMultipartFile("archivo", "hemograma.pdf", "application/pdf", PDF))
                        .with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla"));
        ArgumentCaptor<DocumentUpload> doc = ArgumentCaptor.forClass(DocumentUpload.class);
        verify(cartilla).agregar(eq(1L), any(), doc.capture());
        assertEquals("application/pdf", doc.getValue().mimeType());
    }

    @Test
    void unaFotoDeLaGaleriaComoOtroArchivoTambienSeGuardaConDocumento() throws Exception {
        // DEC-27: la galería entra por «otro archivo».
        mvc.perform(completo().file(new MockMultipartFile("archivo", "IMG_0042.jpeg", "image/jpeg", JPG))
                        .with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla"));
        ArgumentCaptor<DocumentUpload> doc = ArgumentCaptor.forClass(DocumentUpload.class);
        verify(cartilla).agregar(eq(1L), any(), doc.capture());
        assertEquals("image/jpeg", doc.getValue().mimeType());
        assertEquals("IMG_0042.jpeg", doc.getValue().originalName());
    }

    @Test
    void unArchivoVacioEsComoNoAdjuntarNada() throws Exception {
        mvc.perform(completo().file(new MockMultipartFile("foto", "", "application/octet-stream", new byte[0]))
                        .with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla"));
        verify(cartilla).agregar(eq(1L), any(), isNull());
    }

    @Test
    void unHtmlConNombreDeFotoNoSeGuarda() throws Exception {
        byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        mvc.perform(completo().file(new MockMultipartFile("foto", "receta.jpg", "image/jpeg", html))
                        .with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString(PROBLEMA + "El documento tiene que ser una foto (JPG, PNG o WebP) o un PDF."),
                        not(containsString("Para guardarlo nos faltan estos datos:")))));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void laFotoYOtroArchivoALaVezNoSeGuardan() throws Exception {
        mvc.perform(completo().file(new MockMultipartFile("foto", "a.jpg", "image/jpeg", JPG))
                        .file(new MockMultipartFile("archivo", "b.pdf", "application/pdf", PDF))
                        .with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PROBLEMA + "Adjunta un solo documento: la foto o el otro archivo.")));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void unDocumentoDeMasDeDiezMegasNoSeGuarda() throws Exception {
        byte[] grande = new byte[DocumentUpload.MAX_BYTES + 1];
        System.arraycopy(JPG, 0, grande, 0, JPG.length);
        mvc.perform(completo().file(new MockMultipartFile("foto", "grande.jpg", "image/jpeg", grande))
                        .with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(PROBLEMA + "El documento pesa más de 10 MB.")));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void siFaltaUnDatoPideVolverAAdjuntarElDocumento() throws Exception {
        mvc.perform(envio().file(new MockMultipartFile("foto", "a.jpg", "image/jpeg", JPG))
                        .param("titulo", "Influenza").param("categoria", "VACUNAS")
                        .with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder(
                        "Vuelve a adjuntar el documento: no se guarda hasta que el registro esté completo.",
                        "Para guardarlo nos faltan estos datos:", "La fecha que aparece en el papel.")));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void unTituloOUnaInstitucionDemasiadoLargosSeNombran() throws Exception {
        mvc.perform(envio().param("titulo", "x".repeat(161)).param("categoria", "OTROS").param("fecha", "2026-01-01")
                        .param("institucion", "y".repeat(161)).with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsInOrder(
                        "Qué dice el papel puede tener hasta 160 caracteres.",
                        "Dónde se lo dieron puede tener hasta 160 caracteres.")));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void losCaracteresDeControlNoLleganALaBase() throws Exception {
        // Guardián (RS-12): PostgreSQL rechaza el carácter nulo; pegado desde otro lado no debe dar un 500.
        mvc.perform(envio().param("titulo", "Hemo\u0000grama\ncompleto").param("categoria", "LABORATORIOS")
                        .param("fecha", "2026-02-01").param("institucion", "\tLab\u0007 Central ")
                        .with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla"));
        ArgumentCaptor<NuevoRegistro> nuevo = ArgumentCaptor.forClass(NuevoRegistro.class);
        verify(cartilla).agregar(eq(1L), nuevo.capture(), isNull());
        assertEquals("Hemo grama completo", nuevo.getValue().titulo());
        assertEquals("Lab  Central", nuevo.getValue().institucion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-01-01", "1899-12-31", "2101-01-01", "+6000000-01-01"})
    void unaFechaFueraDeRangoCuentaComoFaltante(String fecha) throws Exception {
        // P-20: rango provisional; fuera de él la base guarda absurdos o falla.
        mvc.perform(envio().param("titulo", "Hemograma").param("categoria", "LABORATORIOS").param("fecha", fecha)
                        .with(user(ana)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"falta-fecha\"")));
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1900-01-01", "2100-12-31"})
    void losBordesDelRangoSeAceptan(String fecha) throws Exception {
        mvc.perform(envio().param("titulo", "Hemograma").param("categoria", "LABORATORIOS").param("fecha", fecha)
                        .with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/cartilla"));
    }

    @Test
    void sinCsrfNoSeGuarda() throws Exception {
        mvc.perform(completo().with(user(ana))).andExpect(status().isForbidden());
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    @Test
    void sinSesionVaAlLoginYUnProfesionalNoAgregaNada() throws Exception {
        mvc.perform(get("/cartilla/nuevo"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/"));
        mvc.perform(get("/cartilla/nuevo").with(user(rivera))).andExpect(status().isForbidden());
        mvc.perform(completo().with(user(rivera)).with(csrf())).andExpect(status().isForbidden());
        verify(cartilla, never()).agregar(anyLong(), any(), any());
    }

    private static MockMultipartHttpServletRequestBuilder envio() {
        return multipart("/cartilla/nuevo");
    }

    private static MockMultipartHttpServletRequestBuilder completo() {
        return (MockMultipartHttpServletRequestBuilder) envio().param("titulo", "  Influenza · dosis anual ")
                .param("categoria", "VACUNAS").param("fecha", "2026-04-02").param("institucion", "Minsa");
    }
}
