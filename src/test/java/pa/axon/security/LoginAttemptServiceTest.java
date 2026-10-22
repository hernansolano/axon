package pa.axon.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoginAttemptServiceTest {

    LoginAttemptRepository repo = mock(LoginAttemptRepository.class);
    LoginAttemptService service = new LoginAttemptService(repo);

    @Test
    void guardaTipoIdentificadorOrigenYMotivo() {
        service.record(AccountType.PACIENTE, "8-912-2043", "127.0.0.1", "BadCredentialsException");
        var captor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(repo).save(captor.capture());
        assertEquals(AccountType.PACIENTE, captor.getValue().getAccountType());
        assertEquals("8-912-2043", captor.getValue().getIdentifier());
        assertEquals("127.0.0.1", captor.getValue().getOrigin());
    }

    @Test
    void limpiaCaracteresDeControlYRecortaElIdentificador() {
        // Sin saltos de línea no se puede falsear una entrada del log.
        assertEquals("MED-4471FALSO", LoginAttemptService.clean("  MED-4471\r\nFALSO "));
        assertEquals(20, LoginAttemptService.clean("x".repeat(500)).length());
        assertEquals("", LoginAttemptService.clean(null));
    }

    @Test
    void enElLogSoloSeVeElFinalDelIdentificador() {
        assertEquals("***043", LoginAttemptService.mask("8-912-2043"));
        assertEquals("***", LoginAttemptService.mask("ab"));
        assertEquals("(vacío)", LoginAttemptService.mask(" "));
    }

    @Test
    void siNoSePuedeGuardarElLoginNoSeRompe() {
        when(repo.save(any())).thenThrow(new IllegalStateException("base caída"));
        assertDoesNotThrow(() -> service.record(AccountType.PROFESIONAL, "MED-1", null, "BadCredentialsException"));
    }
}
