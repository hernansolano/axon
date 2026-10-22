package pa.axon;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.CartillaService;
import pa.axon.domain.CartillaService.ArchivoVista;
import pa.axon.domain.CartillaService.GrupoVista;
import pa.axon.domain.CartillaService.RegistroVista;
import pa.axon.domain.Category;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.ConsultaService.Acceso;
import pa.axon.domain.ConsultaService.Canje;
import pa.axon.domain.ConsultaService.ConsultaVista;
import pa.axon.domain.ConsultaService.Estado;
import pa.axon.domain.ConsultaService.Pedido;
import pa.axon.domain.ConsultaService.Resultado;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.PermissionService;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;

/**
 * H4: el canje (UI.md 15 y 16), la consulta (17) y lo fuera del permiso (18). Cada código que no sirve
 * tiene su mensaje, la consulta muestra solo lo que llega del servicio con el aviso de que no es un
 * expediente, el estado no trae datos de la cartilla y un permiso ajeno es 404 (HU-08, CP-09, CP-10).
 * Capa web con el servicio simulado.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class ConsultaWebTest {

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

    UUID receta = UUID.fromString("0b0c6c1e-6b2a-4c55-9f53-2f5b8f0f6a11");
    ConsultaVista vista = new ConsultaVista(7L, "Ana Cedeño", "AC", "Entrega de resultados", 2225, 3600,
            List.of(Category.MEDICAMENTOS),
            List.of(Category.DIAGNOSTICOS, Category.LABORATORIOS, Category.VACUNAS, Category.OTROS),
            List.of(new GrupoVista(Category.MEDICAMENTOS, "1 registro", List.of(
                    new RegistroVista(10L, "Losartán 50 mg", "1 tableta diaria", "enero 2026 · Clínica del Este",
                            true, "PDF", receta)))));

    @Test
    void laPantallaDeCanjeEsUnCampoYNadaMas() throws Exception {
        mvc.perform(get("/pro/canjear").with(user(rivera)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Teclea el código del paciente"),
                        containsString("name=\"codigo\""),
                        containsString("data-testid=\"boton-canjear\""),
                        containsString("Esta pantalla está vacía a propósito."),
                        not(containsString("pantalla-pendiente")),
                        not(containsString("alerta-codigo")))));
    }

    @Test
    void canjearLlevaALaConsultaDelPermiso() throws Exception {
        when(consultas.canjear(eq(1L), eq("MED-4471"), eq("aft un4"), any())).thenReturn(new Canje(Resultado.CANJEADO, 7L));
        mvc.perform(post("/pro/canjear").with(user(rivera)).with(csrf()).param("codigo", "aft un4"))
                .andExpect(redirectedUrl("/pro/consulta/7"));
    }

    @ParameterizedTest
    @EnumSource(value = Resultado.class, names = {"VACIO", "INEXISTENTE", "VENCIDO", "REVOCADO", "AJENO", "BLOQUEADO"})
    void cadaCodigoQueNoSirveTieneSuMensaje(Resultado resultado) throws Exception {
        // HU-08, criterio 5: nunca un error genérico.
        when(consultas.canjear(anyLong(), any(), any(), any())).thenReturn(new Canje(resultado, null));
        mvc.perform(post("/pro/canjear").with(user(rivera)).with(csrf()).param("codigo", "KKK-KKK"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"alerta-codigo-" + resultado.getSlug() + "\""),
                        containsString(resultado.getTitulo()),
                        containsString(resultado.getTexto()),
                        containsString("value=\"KKK-KKK\""))));
    }

    @Test
    void losTresMensajesDelCanjeSonLosDeV4() {
        assertEquals("Ese código no existe", Resultado.INEXISTENTE.getTitulo());
        assertEquals("Ese código ya venció", Resultado.VENCIDO.getTitulo());
        assertEquals("La paciente revocó este código", Resultado.REVOCADO.getTitulo());
        assertEquals("invalido", Resultado.INEXISTENTE.getSlug());
    }

    @Test
    void canjearSinCsrfOSiendoPacienteSeRechaza() throws Exception {
        mvc.perform(post("/pro/canjear").with(user(rivera)).param("codigo", "AFT-UN4")).andExpect(status().isForbidden());
        mvc.perform(post("/pro/canjear").with(user(ana)).with(csrf()).param("codigo", "AFT-UN4")).andExpect(status().isForbidden());
        mvc.perform(post("/pro/canjear").with(csrf()).param("codigo", "AFT-UN4")).andExpect(status().is3xxRedirection());
        verify(consultas, never()).canjear(anyLong(), any(), any(), any());
    }

    @Test
    void laConsultaMuestraLoAutorizadoElAvisoYElTiempoRestante() throws Exception {
        // HU-08, criterios 4, 8 y 9 (UI.md 17).
        when(consultas.consulta(1L, 7L)).thenReturn(Optional.of(new Acceso(vista, null)));
        mvc.perform(get("/pro/consulta/7").with(user(rivera)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Ana Cedeño"),
                        containsString("Entrega de resultados"),
                        containsString("No es un expediente clínico completo"),
                        containsString("data-testid=\"aviso-no-expediente\""),
                        containsString("Quedan"),
                        containsString("37:05"),
                        containsString("data-restante=\"2225\""),
                        containsString("Autorizado por la paciente"),
                        containsString("data-testid=\"grupo-medicamentos\""),
                        containsString("Losartán 50 mg"),
                        containsString("data-src=\"/pro/consulta/7/documento/" + receta + "\""),
                        containsString("Abrir el documento"),
                        containsString("data-estado-url=\"/pro/consulta/7/estado\""),
                        containsString("href=\"/pro/consulta/7/vacunas\""),
                        containsString("Pedir vacunas"),
                        containsString("Pedir diagnósticos y antecedentes"),
                        not(containsString("data-testid=\"grupo-vacunas\"")),
                        not(containsString("Pedir medicamentos y alergias")),
                        not(containsString("/documento/" + receta + "\" ")))));
    }

    @Test
    void conElPermisoCortadoLaConsultaNoMuestraLaCartilla() throws Exception {
        when(consultas.consulta(1L, 7L)).thenReturn(Optional.of(new Acceso(null, Resultado.REVOCADO)));
        mvc.perform(get("/pro/consulta/7").with(user(rivera)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"consulta-cortada\""),
                        containsString("La paciente revocó este código"),
                        not(containsString("data-consulta-contenido")),
                        not(containsString("Autorizado por la paciente")))));
    }

    @Test
    void unaConsultaAjenaEs404() throws Exception {
        when(consultas.consulta(1L, 8L)).thenReturn(Optional.empty());
        mvc.perform(get("/pro/consulta/8").with(user(rivera))).andExpect(status().isNotFound());
        mvc.perform(get("/pro/consulta/8/estado").with(user(rivera))).andExpect(status().isNotFound());
        mvc.perform(get("/pro/consulta/8/vacunas").with(user(rivera))).andExpect(status().isNotFound());
        mvc.perform(get("/pro/consulta/abc").with(user(rivera))).andExpect(status().isNotFound());
    }

    @Test
    void elEstadoSoloDiceSiSigueVigente() throws Exception {
        // DEC-30: la ruta que pregunta la pantalla cada 5 s no trae nada de la cartilla.
        when(consultas.estado(1L, 7L)).thenReturn(Optional.of(new Estado(true, 120, null)));
        mvc.perform(get("/pro/consulta/7/estado").with(user(rivera)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.vigente").value(true))
                .andExpect(jsonPath("$.restanteSeg").value(120))
                .andExpect(jsonPath("$.titulo").doesNotExist());

        when(consultas.estado(1L, 7L)).thenReturn(Optional.of(new Estado(false, 0, Resultado.VENCIDO)));
        mvc.perform(get("/pro/consulta/7/estado").with(user(rivera)))
                .andExpect(jsonPath("$.vigente").value(false))
                .andExpect(jsonPath("$.motivo").value("vencido"))
                .andExpect(jsonPath("$.titulo").value("Ese código ya venció"));
    }

    @Test
    void unaCategoriaFueraDelPermisoSeExplicaYOfreceLaSolicitud() throws Exception {
        // CP-10 (UI.md 18).
        when(consultas.estado(1L, 7L)).thenReturn(Optional.of(new Estado(true, 120, null)));
        when(consultas.denegar(1L, 7L, Category.VACUNAS)).thenReturn(Optional.of(new Pedido(Category.VACUNAS, "Medicamentos y alergias")));
        mvc.perform(get("/pro/consulta/7/vacunas").with(user(rivera)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"alerta-fuera-de-alcance\""),
                        containsString("Vacunas no está en este permiso"),
                        containsString("No es una falla del sistema: es el sistema haciendo su trabajo."),
                        containsString("Lo que sí autorizó"),
                        containsString("Medicamentos y alergias"),
                        containsString("Este intento quedó anotado en la actividad de la paciente."),
                        containsString("action=\"/pro/consulta/7/solicitar/vacunas\""),
                        containsString("Volver a lo autorizado"))));
    }

    @Test
    void unaCategoriaAutorizadaVuelveALaConsulta() throws Exception {
        when(consultas.estado(1L, 7L)).thenReturn(Optional.of(new Estado(true, 120, null)));
        when(consultas.denegar(1L, 7L, Category.MEDICAMENTOS)).thenReturn(Optional.empty());
        mvc.perform(get("/pro/consulta/7/medicamentos").with(user(rivera))).andExpect(redirectedUrl("/pro/consulta/7"));
        mvc.perform(get("/pro/consulta/7/inventada").with(user(rivera))).andExpect(status().isNotFound());
    }

    @Test
    void laSolicitudDeAmpliacionAvisaQueSeEnvio() throws Exception {
        // PB-19 (DEC-33).
        when(consultas.estado(1L, 7L)).thenReturn(Optional.of(new Estado(true, 120, null)));
        when(consultas.solicitar(1L, 7L, Category.VACUNAS)).thenReturn(Optional.of(new Pedido(Category.VACUNAS, "Medicamentos y alergias")));
        mvc.perform(post("/pro/consulta/7/solicitar/vacunas").with(user(rivera)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"solicitud-enviada\""),
                        not(containsString("boton-solicitar-ampliacion")))));
        mvc.perform(post("/pro/consulta/7/solicitar/vacunas").with(user(rivera))).andExpect(status().isForbidden());
    }

    @Test
    void elDocumentoSaleSinCacheYEnSandbox() throws Exception {
        when(consultas.documento(1L, 7L, receta)).thenReturn(Optional.of(new ArchivoVista("application/pdf", new byte[] {37, 80})));
        mvc.perform(get("/pro/consulta/7/documento/" + receta).with(user(rivera)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        UUID otro = UUID.randomUUID();
        when(consultas.documento(1L, 7L, otro)).thenReturn(Optional.empty());
        mvc.perform(get("/pro/consulta/7/documento/" + otro).with(user(rivera))).andExpect(status().isNotFound());
    }

    @Test
    void unaPacienteNoEntraANadaDelProfesional() throws Exception {
        mvc.perform(get("/pro/consulta/7").with(user(ana))).andExpect(status().isForbidden());
        mvc.perform(get("/pro/consulta/7/estado").with(user(ana))).andExpect(status().isForbidden());
        mvc.perform(get("/pro/consulta/7/documento/" + receta).with(user(ana))).andExpect(status().isForbidden());
        verify(consultas, never()).consulta(anyLong(), anyLong());
        verify(consultas, never()).documento(anyLong(), anyLong(), any());
    }
}
