package pa.axon;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.CartillaService;
import pa.axon.domain.Category;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.Permission;
import pa.axon.domain.PermissionService;
import pa.axon.domain.PermissionService.NuevoPermiso;
import pa.axon.domain.PermissionService.Opcion;
import pa.axon.domain.PermissionService.PermisoVista;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.domain.ShareDuration;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;

/**
 * H3: compartir (UI.md 09), el código (10) y los permisos (11). Lo que queda fuera se dice (HU-07), lo
 * que llega se vuelve a revisar en el servidor, los tres estados llevan su marca y un permiso ajeno es
 * 404 (HU-09, CP-08, CP-11). Capa web con el servicio simulado.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class PermisosWebTest {

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

    PermisoVista activo = vista(7L, "AFT-UN4", Permission.Status.ACTIVO, "Sin canjear", 7140, 7200);
    PermisoVista vencido = vista(8L, "K2P-77M", Permission.Status.VENCIDO, "Dra. Rivera · Clínica del Este", 0, 7200);
    PermisoVista revocado = vista(9L, "H7N-34D", Permission.Status.REVOCADO, "Sin canjear", 0, 86400);

    @BeforeEach
    void opciones() {
        when(permisos.opciones(1L)).thenReturn(List.of(
                new Opcion(Category.DIAGNOSTICOS, "2 registros"), new Opcion(Category.MEDICAMENTOS, "2 registros"),
                new Opcion(Category.LABORATORIOS, "1 registro"), new Opcion(Category.VACUNAS, "1 registro"),
                new Opcion(Category.OTROS, "Nada guardado")));
    }

    @Test
    void compartirMuestraLasCincoCategoriasConSuConteoYLoQueQuedaFuera() throws Exception {
        String html = mvc.perform(get("/compartir").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("¿Qué le vas a mostrar?"),
                        containsString("data-testid=\"categoria-diagnosticos\""),
                        containsString("data-testid=\"categoria-otros\""),
                        containsString("Nada guardado"),
                        containsString("Queda fuera"),
                        containsString("Diagnósticos y antecedentes, medicamentos y alergias, laboratorios y estudios, vacunas y otros documentos"),
                        containsString("El médico no lo ve ni sabe que existe."),
                        containsString("Vence solo en <span data-vigencia-texto>2 horas</span>"),
                        containsString("Segunda opinión"),
                        containsString("data-testid=\"boton-generar-codigo\""),
                        not(containsString("alerta-compartir")))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Ninguna categoría viene marcada (DEC-29) y la vigencia empieza en 2 horas.
        for (Category c : Category.values()) {
            assertFalse(marcado(html, "alcance", c.name()), c.name());
        }
        assertTrue(marcado(html, "vigencia", "120"));
        assertFalse(marcado(html, "vigencia", "15"));
    }

    @Test
    void crearLlevaAlCodigoConElAlcanceLaVigenciaYElPropositoElegidos() throws Exception {
        when(permisos.crear(any(), any())).thenReturn(7L);
        mvc.perform(post("/compartir").with(user(ana)).with(csrf())
                        .param("alcance", "VACUNAS", "MEDICAMENTOS").param("vigencia", "15").param("proposito", "Control prenatal"))
                .andExpect(redirectedUrl("/compartir/7"));
        ArgumentCaptor<NuevoPermiso> nuevo = ArgumentCaptor.forClass(NuevoPermiso.class);
        verify(permisos).crear(eq(1L), nuevo.capture());
        assertEquals(EnumSet.of(Category.MEDICAMENTOS, Category.VACUNAS), nuevo.getValue().alcance());
        assertEquals(ShareDuration.MIN_15, nuevo.getValue().vigencia());
        assertEquals("Control prenatal", nuevo.getValue().proposito());
    }

    @Test
    void sinCategoriaNiVigenciaNiPropositoValidosNombraLoQueFaltaYNoCreaNada() throws Exception {
        mvc.perform(post("/compartir").with(user(ana)).with(csrf())
                        .param("vigencia", "9999").param("proposito", "Ver todo"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"alerta-compartir\""),
                        stringContainsInOrder("Marca al menos una categoría.",
                                "Por cuánto tiempo: 15 min, 2 horas o 24 horas.",
                                "Para qué: elige una de las opciones de la lista."),
                        not(containsString("obligatorio")))));
        verify(permisos, never()).crear(any(), any());
    }

    @Test
    void sinPropositoNiVigenciaEnLaSolicitudNombraLoQueFaltaEnVezDeFallar() throws Exception {
        // Una solicitud armada a mano, sin los campos: antes daba 500 por List.of().contains(null).
        mvc.perform(post("/compartir").with(user(ana)).with(csrf()).param("alcance", "MEDICAMENTOS"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"alerta-compartir\""),
                        stringContainsInOrder("Por cuánto tiempo: 15 min, 2 horas o 24 horas.",
                                "Para qué: elige una de las opciones de la lista."))));
        verify(permisos, never()).crear(any(), any());
    }

    @Test
    void unaCategoriaQueNoExisteSeRechazaYLoMarcadoSeConserva() throws Exception {
        String html = mvc.perform(post("/compartir").with(user(ana)).with(csrf())
                        .param("alcance", "VACUNAS", "TODO").param("vigencia", "1440").param("proposito", "Consulta general"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Una de las categorías marcadas no existe.")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(marcado(html, "alcance", "VACUNAS"));
        assertFalse(marcado(html, "alcance", "MEDICAMENTOS"));
        assertTrue(marcado(html, "vigencia", "1440"));
        verify(permisos, never()).crear(any(), any());
    }

    @Test
    void crearSinCsrfSeRechaza() throws Exception {
        mvc.perform(post("/compartir").with(user(ana)).param("alcance", "VACUNAS").param("vigencia", "15")
                        .param("proposito", "Consulta general"))
                .andExpect(status().isForbidden());
        verify(permisos, never()).crear(any(), any());
    }

    @Test
    void elCodigoSeVeGrandeConElContadorLoQuePodraVerYLoQueQuedaFuera() throws Exception {
        when(permisos.uno(1L, 7L)).thenReturn(Optional.of(activo));
        mvc.perform(get("/compartir/7").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Dicta este código"),
                        containsString("data-codigo=\"AFT-UN4\""),
                        containsString("AFT · UN4"),
                        containsString("El médico lo teclea en su cuenta profesional."),
                        containsString("data-testid=\"estado-permiso-activo\""),
                        containsString("Permiso activo"),
                        containsString("1 h 59 min"),
                        containsString("data-restante=\"7140\""),
                        containsString("Medicamentos y alergias"),
                        containsString("Queda fuera: diagnósticos y antecedentes, laboratorios y estudios, vacunas y otros documentos."),
                        containsString("href=\"/permisos\""),
                        containsString("action=\"/permisos/7/revocar\""),
                        containsString("Revocar este permiso"))));
    }

    @Test
    void elCodigoRevocadoDiceSuEstadoYYaNoOfreceRevocar() throws Exception {
        when(permisos.uno(1L, 9L)).thenReturn(Optional.of(revocado));
        mvc.perform(get("/compartir/9").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"estado-permiso-revocado\""),
                        containsString("Permiso revocado"),
                        not(containsString("data-contador")),
                        not(containsString("Revocar este permiso")))));
    }

    @Test
    void unCodigoAjenoOInexistenteEs404() throws Exception {
        when(permisos.uno(1L, 99L)).thenReturn(Optional.empty());
        mvc.perform(get("/compartir/99").with(user(ana))).andExpect(status().isNotFound());
        mvc.perform(get("/compartir/abc").with(user(ana))).andExpect(status().isNotFound());
    }

    @Test
    void losPermisosMuestranLosTresEstadosConSuMarcaYSoloLosActivosSeRevocan() throws Exception {
        when(permisos.deLaPaciente(1L)).thenReturn(List.of(activo, vencido, revocado));
        mvc.perform(get("/permisos").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Quién puede ver tu cartilla"),
                        containsString("data-testid=\"permiso-7\""),
                        containsString("data-testid=\"estado-permiso-activo\""),
                        containsString("Activo · <span data-restante-texto>1 h 59 min</span>"),
                        containsString("data-testid=\"estado-permiso-vencido\""),
                        containsString(">schedule</span>Vencido"),
                        containsString("data-testid=\"estado-permiso-revocado\""),
                        containsString(">block</span>Revocado por ti"),
                        containsString("Dra. Rivera · Clínica del Este"),
                        containsString("Alcance: medicamentos y alergias."),
                        containsString("Consulta general · generado hoy 8:55"),
                        containsString("data-testid=\"revocar-7\""),
                        not(containsString("data-testid=\"revocar-8\"")),
                        not(containsString("data-testid=\"revocar-9\"")),
                        not(containsString("permisos-vacio")))));
    }

    @Test
    void sinPermisosLoDice() throws Exception {
        when(permisos.deLaPaciente(1L)).thenReturn(List.of());
        mvc.perform(get("/permisos").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Todavía no has compartido tu cartilla.")));
    }

    @Test
    void revocarVuelveALaListaConElAviso() throws Exception {
        String aviso = "El código AFT-UN4 ya no sirve. Lo que vio antes sigue anotado en tu actividad: revocar no borra el historial.";
        when(permisos.revocar(1L, 7L)).thenReturn(Optional.of(aviso));
        mvc.perform(post("/permisos/7/revocar").with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/permisos"))
                .andExpect(flash().attribute("aviso", aviso));
    }

    @Test
    void elAvisoSeMuestraEnLaLista() throws Exception {
        when(permisos.deLaPaciente(1L)).thenReturn(List.of(revocado));
        mvc.perform(get("/permisos").with(user(ana)).flashAttr("aviso", "El código H7N-34D ya no sirve."))
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"aviso-revocado\""),
                        containsString("El código H7N-34D ya no sirve."))));
    }

    @Test
    void revocarUnoAjenoEs404YSinCsrfSeRechaza() throws Exception {
        when(permisos.revocar(1L, 99L)).thenReturn(Optional.empty());
        mvc.perform(post("/permisos/99/revocar").with(user(ana)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/permisos/7/revocar").with(user(ana))).andExpect(status().isForbidden());
        verify(permisos, never()).revocar(1L, 7L);
    }

    @Test
    void unProfesionalNoComparteNiVeNiRevocaPermisos() throws Exception {
        // RS-03: tener cuenta profesional no da acceso a nada de la paciente.
        mvc.perform(get("/compartir").with(user(rivera))).andExpect(status().isForbidden());
        mvc.perform(get("/compartir/7").with(user(rivera))).andExpect(status().isForbidden());
        mvc.perform(get("/permisos").with(user(rivera))).andExpect(status().isForbidden());
        mvc.perform(post("/permisos/7/revocar").with(user(rivera)).with(csrf())).andExpect(status().isForbidden());
        verify(permisos, never()).revocar(anyLong(), anyLong());
        verify(permisos, never()).uno(anyLong(), anyLong());
    }

    @Test
    void sinSesionVaAlLogin() throws Exception {
        mvc.perform(get("/permisos")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/compartir").with(csrf()).param("alcance", "VACUNAS")).andExpect(status().is3xxRedirection());
        verify(permisos, never()).crear(any(), any());
    }

    private static PermisoVista vista(Long id, String codigo, Permission.Status estado, String quien, long restante, long total) {
        return new PermisoVista(id, codigo, estado, quien, List.of(Category.MEDICAMENTOS), "Medicamentos y alergias",
                "Queda fuera: diagnósticos y antecedentes, laboratorios y estudios, vacunas y otros documentos.",
                "Consulta general", "hoy 8:55", restante, total);
    }

    /** Si la casilla o la opción de ese valor sale marcada; el atributo puede venir en otra línea. */
    private static boolean marcado(String html, String name, String value) {
        Matcher input = Pattern.compile("<input[^>]*name=\"" + name + "\" value=\"" + value + "\"[^>]*>").matcher(html);
        assertTrue(input.find(), name + "=" + value);
        return input.group().contains("checked");
    }
}
