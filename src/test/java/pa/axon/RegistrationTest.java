package pa.axon;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import pa.axon.config.SecurityConfig;
import pa.axon.domain.ActivityService;
import pa.axon.domain.CartillaService;
import pa.axon.domain.ConsultaService;
import pa.axon.domain.Patient;
import pa.axon.domain.PatientRegistrationService;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.PermissionService;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.security.AxonUser;
import pa.axon.security.AccountType;
import pa.axon.security.LoginAttemptService;

/**
 * H1b, HU-13: registro de la paciente (CP-18 y la parte de servicios de CP-17). El servicio es el
 * real; solo el repositorio está simulado, así que no hace falta la base.
 */
@WebMvcTest
@Import({SecurityConfig.class, PatientRegistrationService.class})
@ExtendWith(OutputCaptureExtension.class)
class RegistrationTest {

    private static final String CEDULA = "4-123-4567";
    private static final String CLAVE = "otoño-lluvioso";

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
    ActivityService actividad;

    AxonUser ana = AxonUser.patient(1L, "8-912-2043", "Ana Cedeño", "x");
    AxonUser rivera = AxonUser.professional(1L, "MED-4471", "Dra. Rivera", "Clínica del Este", "x");

    @BeforeEach
    void datos() {
        when(patients.existsByCedula("8-912-2043")).thenReturn(true);
        when(patients.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MockHttpServletRequestBuilder registro(String cedula, String nombre, String clave, String confirmar) {
        return post("/registro").with(csrf())
                .param("cedula", cedula).param("nombre", nombre)
                .param("clave", clave).param("claveConfirmar", confirmar);
    }

    private Patient guardada() {
        ArgumentCaptor<Patient> captor = ArgumentCaptor.forClass(Patient.class);
        verify(patients).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void elFormularioEsPublicoYTraeSusCampos() throws Exception {
        mvc.perform(get("/registro"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Crear cuenta")))
                .andExpect(content().string(containsString("data-testid=\"campo-cedula\"")))
                .andExpect(content().string(containsString("data-testid=\"campo-nombre\"")))
                .andExpect(content().string(containsString("data-testid=\"campo-clave\"")))
                .andExpect(content().string(containsString("data-testid=\"campo-clave-confirmar\"")))
                .andExpect(content().string(containsString("data-testid=\"boton-crear-cuenta\"")))
                .andExpect(content().string(containsString("data-testid=\"enlace-entrar\"")));
    }

    @Test
    void unaCedulaNuevaCreaLaCuentaYVaAlLoginSinAbrirSesion() throws Exception {
        mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, CLAVE))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?registrada"))
                .andExpect(unauthenticated());

        Patient nueva = guardada();
        assertEquals(CEDULA, nueva.getCedula());
        assertEquals("Rosa Pinzón", nueva.getNombre());
    }

    @Test
    void aceptaLasCedulasEspecialesYLasGuardaEnMayusculas() throws Exception {
        // DEC-24: E, N, PE, AV y PI, escritas como sea, quedan en una sola forma.
        mvc.perform(registro(" pe-8-1234 ", "Rosa Pinzón", CLAVE, CLAVE))
                .andExpect(redirectedUrl("/?registrada"));
        assertEquals("PE-8-1234", guardada().getCedula());
    }

    @Test
    void laCuentaConCedulaEspecialEntraAunqueLaEscribaEnMinusculas() throws Exception {
        when(patients.findByCedula("1PI-12-345"))
                .thenReturn(Optional.of(new Patient("1PI-12-345", "Rosa Pinzón", encoder.encode(CLAVE))));
        mvc.perform(post("/login").with(csrf()).param("cedula", "1pi-12-345").param("clave", CLAVE))
                .andExpect(redirectedUrl("/cartilla"))
                .andExpect(authenticated().withRoles(AxonUser.PACIENTE));
    }

    @Test
    void elIntentoFallidoSeAnotaConLaCedulaNormalizada() throws Exception {
        // Guardián de acceso (DEC-24): escrita en minúsculas, no puede quedar como otro identificador.
        mvc.perform(post("/login").with(csrf()).param("cedula", " pe-8-1234 ").param("clave", "incorrecta"));
        verify(attempts).record(eq(AccountType.PACIENTE), eq("PE-8-1234"), any(), any());
    }

    @Test
    void laClaveNoPuedeSerLaCedulaAunqueCambieDeMayusculas() throws Exception {
        mvc.perform(registro("E-8-123456", "Rosa Pinzón", "e-8-123456", "e-8-123456"))
                .andExpect(content().string(containsString("La clave no puede ser tu cédula.")));
    }

    @Test
    void laClaveSeGuardaConBCrypt() throws Exception {
        mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, CLAVE));

        String hash = guardada().getHashClave();
        assertTrue(hash.startsWith("$2"), "RS-11: la clave guardada debe ser un hash BCrypt");
        assertTrue(encoder.matches(CLAVE, hash));
    }

    @Test
    void elLoginMuestraElAvisoDeCuentaCreada() throws Exception {
        mvc.perform(get("/").param("registrada", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"aviso-cuenta-creada\"")))
                .andExpect(content().string(containsString("Tu cuenta está lista. Entra con tu cédula y tu clave.")));
    }

    @Test
    void laCuentaNuevaEntraPorElLoginDeSiempre() throws Exception {
        mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, CLAVE));
        Patient nueva = guardada();
        when(patients.findByCedula(CEDULA)).thenReturn(Optional.of(nueva));

        mvc.perform(post("/login").with(csrf()).param("cedula", CEDULA).param("clave", CLAVE))
                .andExpect(redirectedUrl("/cartilla"))
                .andExpect(authenticated().withRoles(AxonUser.PACIENTE));
    }

    @Test
    void unaCedulaRepetidaDaUnMensajeNeutroYNoGuarda() throws Exception {
        mvc.perform(registro("8-912-2043", "Otra persona", CLAVE, CLAVE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"alerta-registro\"")))
                .andExpect(content().string(containsString(
                        "No pudimos crear la cuenta con esos datos. Si ya te registraste, entra con tu cédula y tu clave.")))
                .andExpect(content().string(not(containsString("ya existe"))))
                .andExpect(content().string(not(containsString("ya tiene cuenta"))));
        verify(patients, never()).save(any());
    }

    @Test
    void dosRegistrosSimultaneosConLaMismaCedulaDanElMismoMensajeNeutro() throws Exception {
        when(patients.save(any(Patient.class))).thenThrow(new DataIntegrityViolationException("paciente_cedula_key"));

        mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, CLAVE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No pudimos crear la cuenta con esos datos.")));
    }

    @Test
    void cadaDatoFaltanteSeNombra() throws Exception {
        mvc.perform(registro("", "", "", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-testid=\"alerta-registro\"")))
                .andExpect(content().string(containsString("Falta tu cédula.")))
                .andExpect(content().string(containsString("Falta tu nombre.")))
                .andExpect(content().string(containsString("Falta tu clave.")))
                .andExpect(content().string(containsString("Falta repetir tu clave.")))
                .andExpect(content().string(not(containsString("campos obligatorios"))))
                // Un mensaje por dato: si falta, no se agrega además el de formato.
                .andExpect(content().string(not(containsString("no tiene el formato"))));
        verify(patients, never()).save(any());
    }

    @Test
    void cadaDatoInvalidoSeNombra() throws Exception {
        mvc.perform(registro("8912-2043", "Rosa Pinzón", "corta", "corta"))
                .andExpect(content().string(containsString("La cédula no tiene el formato 8-912-2043.")))
                .andExpect(content().string(containsString("La clave debe tener al menos 8 caracteres.")));

        mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, "otra-clave-larga"))
                .andExpect(content().string(containsString("Las dos claves no coinciden.")));

        mvc.perform(registro(CEDULA, "Rosa Pinzón", CEDULA, CEDULA))
                .andExpect(content().string(containsString("La clave no puede ser tu cédula.")));

        mvc.perform(registro(CEDULA, "R".repeat(121), CLAVE, CLAVE))
                .andExpect(content().string(containsString("El nombre puede tener hasta 120 caracteres.")));

        // Más de 72 bytes: BCrypt no los admite. Debe ser un mensaje, no un error 500.
        String larga = "ñ".repeat(40);
        mvc.perform(registro(CEDULA, "Rosa Pinzón", larga, larga))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("La clave es demasiado larga.")));

        verify(patients, never()).save(any());
    }

    @Test
    void laCedulaYElNombreSeConservanPeroLaClaveNoVuelveEnLaRespuesta() throws Exception {
        String html = mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, "otra-clave-larga"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(html.contains("value=\"4-123-4567\""));
        assertTrue(html.contains("value=\"Rosa Pinzón\""));
        assertFalse(html.contains(CLAVE), "la clave no debe volver en el HTML");
        assertFalse(html.contains("otra-clave-larga"), "la confirmación no debe volver en el HTML");
    }

    @Test
    void niLaClaveNiSuHashLleganAlLog(CapturedOutput salida) throws Exception {
        // Clave propia: si otra prueba falla, MockMvc imprime su solicitud con CLAVE incluida.
        String clave = "solo-en-esta-prueba";
        mvc.perform(registro(CEDULA, "Rosa Pinzón", clave, clave));

        assertFalse(salida.getAll().contains(clave));
        assertFalse(salida.getAll().contains(guardada().getHashClave()));
    }

    @Test
    void unProfesionalConSesionNoCreaCuentas() throws Exception {
        mvc.perform(get("/registro").with(user(rivera))).andExpect(status().isForbidden());
        mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, CLAVE).with(user(rivera)))
                .andExpect(status().isForbidden());
        verify(patients, never()).save(any());
    }

    @Test
    void unaPacienteConSesionVuelveASuCartillaSinCrearOtraCuenta() throws Exception {
        mvc.perform(get("/registro").with(user(ana))).andExpect(redirectedUrl("/cartilla"));
        mvc.perform(registro(CEDULA, "Rosa Pinzón", CLAVE, CLAVE).with(user(ana)))
                .andExpect(redirectedUrl("/cartilla"));
        verify(patients, never()).save(any());
    }

    @Test
    void sinTokenCsrfNoSeRegistra() throws Exception {
        mvc.perform(post("/registro").param("cedula", CEDULA).param("nombre", "Rosa Pinzón")
                        .param("clave", CLAVE).param("claveConfirmar", CLAVE))
                .andExpect(status().isForbidden());
        verify(patients, never()).save(any());
    }

    @Test
    void noHayRutaQueRegistreProfesionales() throws Exception {
        // Sin sesión, todo /pro/** que no sea el login lleva al login del profesional.
        mvc.perform(post("/pro/registro").with(csrf()).param("idoneidad", "MED-9999").param("clave", CLAVE))
                .andExpect(redirectedUrl("http://localhost/pro"));
        mvc.perform(get("/pro/registro")).andExpect(redirectedUrl("http://localhost/pro"));
        // Con sesión de profesional pasa la seguridad, y no hay nada detrás.
        mvc.perform(post("/pro/registro").with(csrf()).with(user(rivera))).andExpect(status().isNotFound());
    }
}
