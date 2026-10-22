package pa.axon;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.ActivityService.Entrada;
import pa.axon.domain.ActivityService.Marca;
import pa.axon.domain.CartillaService;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.PermissionService;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;

/**
 * H5: Actividad (UI.md 13, HU-10). Las entradas en el orden del servicio, cada una con su marca, y nada
 * que edite ni borre: ni formularios en la página ni otro método que GET (CP-12, RS-07). Capa web con
 * el servicio simulado.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class ActividadWebTest {

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

    List<Entrada> cinco = List.of(
            new Entrada(5L, Marca.PROPIA, "Tú", "Revocaste el permiso AFT-UN4 antes de que venciera", "ahora mismo"),
            new Entrada(4L, Marca.DENEGADA, "Dra. Rivera · Clínica del Este", "Pidió vacunas, fuera del alcance · denegado", "hoy 9:04"),
            new Entrada(3L, Marca.CONSULTA, "Dra. Rivera · Clínica del Este", "Vio medicamentos y alergias", "hoy 9:02"),
            new Entrada(2L, Marca.EMERGENCIA, "Dra. Batista · Urgencias", "Abrió tu ficha de emergencia · paciente inconsciente tras caída", "ayer 23:20"),
            new Entrada(1L, Marca.PROPIA, "Tú", "Generaste el código AFT-UN4 para medicamentos y alergias", "12 oct, 8:55"));

    @Test
    void muestraLasEntradasEnOrdenConSuMarcaYElConteo() throws Exception {
        when(actividad.deLaPaciente(1L)).thenReturn(cinco);

        mvc.perform(get("/actividad").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Quién vio qué, y cuándo"),
                        containsString("No se puede editar ni borrar · <span data-testid=\"actividad-conteo\">5 entradas</span>"),
                        stringContainsInOrder("data-testid=\"nav-actividad\"", "aria-current=\"page\"", "<main"),
                        stringContainsInOrder("evento-5", "evento-4", "evento-3", "evento-2", "evento-1"),
                        containsString("data-marca=\"propia\""),
                        containsString("data-marca=\"consulta\""),
                        containsString("data-marca=\"denegada\""),
                        containsString("data-marca=\"emergencia\""),
                        containsString("Intento denegado"),
                        containsString("Acceso de emergencia"),
                        containsString("Consulta autorizada"),
                        containsString("Acción tuya"),
                        containsString("Dra. Rivera · Clínica del Este"),
                        not(containsString("pantalla-pendiente")))));
    }

    @Test
    void laPaginaNoTraeNadaQueEditeOBorre() throws Exception {
        when(actividad.deLaPaciente(1L)).thenReturn(cinco);

        String html = mvc.perform(get("/actividad").with(user(ana)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // El único formulario es el de salir, del marco; dentro de <main> no hay ninguno ni botón alguno.
        String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
        assertFalse(main.contains("<form"), "Actividad no puede tener formularios");
        assertFalse(main.contains("<button"), "Actividad no puede tener botones");
        assertFalse(main.contains("contenteditable"));
    }

    @Test
    void sinActividadLoDiceYCuentaCero() throws Exception {
        when(actividad.deLaPaciente(1L)).thenReturn(List.of());

        mvc.perform(get("/actividad").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("data-testid=\"actividad-vacia\""),
                        containsString("0 entradas"),
                        not(containsString("lista-actividad")))));
    }

    @Test
    void unaSolaEntradaSeCuentaEnSingular() throws Exception {
        when(actividad.deLaPaciente(1L)).thenReturn(cinco.subList(0, 1));

        mvc.perform(get("/actividad").with(user(ana)))
                .andExpect(content().string(containsString(">1 entrada<")));
    }

    @Test
    void ningunMetodoQueNoSeaGetTocaLaBitacora() throws Exception {
        for (MockHttpServletRequestBuilder intento : List.of(post("/actividad"), put("/actividad"),
                patch("/actividad"), delete("/actividad"), post("/actividad/5/eliminar"), delete("/actividad/5"))) {
            int codigo = mvc.perform(intento.with(user(ana)).with(csrf())).andReturn().getResponse().getStatus();
            assertTrue(codigo == 404 || codigo == 405,
                    intento + " respondió " + codigo);
        }
        verify(actividad, never()).deLaPaciente(anyLong());
    }

    @Test
    void elProfesionalNoVeLaActividadDeNadie() throws Exception {
        mvc.perform(get("/actividad").with(user(rivera)))
                .andExpect(status().isForbidden());
        verify(actividad, never()).deLaPaciente(anyLong());
    }

    @Test
    void sinSesionVaAlLogin() throws Exception {
        mvc.perform(get("/actividad"))
                .andExpect(status().is3xxRedirection());
        verify(actividad, never()).deLaPaciente(anyLong());
    }
}
