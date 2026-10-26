package pa.axon;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.CartillaService;
import pa.axon.domain.CartillaService.FichaConfig;
import pa.axon.domain.CartillaService.LineaFicha;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.PermissionService;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AxonUser;
import pa.axon.security.LoginAttemptService;

/**
 * H9 (HU-06): la paciente elige línea por línea qué verá urgencias, ve el resumen de lo único que se
 * verá, y una ficha vacía lo dice. La ficha es siempre la de la sesión. Capa web con el servicio simulado.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class FichaEmergenciaWebTest {

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
    void muestraCadaRegistroComoUnaLineaYElResumenDeLoMarcado() throws Exception {
        when(cartilla.ficha(1L)).thenReturn(new FichaConfig(List.of(
                new LineaFicha(4L, "medicamentos y alergias", "Penicilina, alergia", true),
                new LineaFicha(3L, "medicamentos y alergias", "Losartán 50 mg", true),
                new LineaFicha(2L, "diagnósticos y antecedentes", "Dolor lumbar recurrente", false)),
                "Penicilina, alergia, Losartán 50 mg"));
        mvc.perform(get("/emergencia").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Ficha de emergencia"),
                        containsString("sin código de por medio"),
                        containsString("data-testid=\"ficha-registro-4\""),
                        containsString("data-testid=\"ficha-registro-2\""),
                        containsString("En urgencias se verán"),
                        containsString("Penicilina, alergia, Losartán 50 mg"),
                        containsString("ni tus documentos, ni tu bitácora, ni el resto de la cartilla."),
                        containsString("cada apertura queda registrada en Actividad"),
                        not(containsString("Llega en H9")))));
    }

    @Test
    void unaFichaVaciaDiceQueNoSeVeraNada() throws Exception {
        when(cartilla.ficha(1L)).thenReturn(new FichaConfig(List.of(
                new LineaFicha(2L, "diagnósticos y antecedentes", "Dolor lumbar recurrente", false)), ""));
        mvc.perform(get("/emergencia").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tu ficha está vacía: en urgencias no se verá ningún dato tuyo.")));
    }

    @Test
    void guardarMandaSoloIdsNumericosDeLaSesion() throws Exception {
        mvc.perform(post("/emergencia").param("registro", "3", "4", "abc", "3", "-1").with(user(ana)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/emergencia?guardada"));
        verify(cartilla).guardarFicha(1L, List.of(3L, 4L));
    }

    @Test
    void guardarSinNadaMarcadoVaciaLaFicha() throws Exception {
        mvc.perform(post("/emergencia").with(user(ana)).with(csrf()))
                .andExpect(redirectedUrl("/emergencia?guardada"));
        verify(cartilla).guardarFicha(1L, List.of());
    }

    @Test
    void sinCsrfNoSeGuarda() throws Exception {
        mvc.perform(post("/emergencia").param("registro", "3").with(user(ana)))
                .andExpect(status().isForbidden());
        verify(cartilla, never()).guardarFicha(anyLong(), anyCollection());
    }

    @Test
    void unProfesionalNoConfiguraNingunaFicha() throws Exception {
        mvc.perform(get("/emergencia").with(user(rivera))).andExpect(status().isForbidden());
        mvc.perform(post("/emergencia").param("registro", "3").with(user(rivera)).with(csrf()))
                .andExpect(status().isForbidden());
        verify(cartilla, never()).guardarFicha(anyLong(), anyCollection());
    }
}
