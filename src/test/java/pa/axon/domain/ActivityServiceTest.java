package pa.axon.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import pa.axon.domain.ActivityService.Entrada;
import pa.axon.domain.ActivityService.Marca;

/**
 * H5: la actividad de la paciente (HU-10). Lo propio, lo autorizado, lo denegado y la emergencia se
 * distinguen; cada consulta nombra al profesional y lo que vio; los códigos solo salen de permisos de
 * la misma paciente. Repositorios simulados y un reloj fijo.
 */
class ActivityServiceTest {

    private static final long ANA = 1L;
    private static final long RIVERA = 3L;
    /** Lunes 12 de octubre de 2026, 8:55 en Panamá. */
    private static final Instant LUNES = Instant.parse("2026-10-12T13:55:00Z");

    AuditRepository audit = mock(AuditRepository.class);
    PermissionRepository permissions = mock(PermissionRepository.class);
    ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    ActivityService service;

    @BeforeEach
    void setUp() {
        service = new ActivityService(audit, permissions, professionals,
                Clock.fixed(LUNES, ZoneId.of("America/Panama")));
        Permission p = new Permission(ANA, "AFT-UN4", List.of(Category.MEDICAMENTOS), "Consulta general",
                LUNES.minus(Duration.ofHours(1)), LUNES.plus(Duration.ofHours(1)));
        ReflectionTestUtils.setField(p, "id", 7L);
        when(permissions.findByPatientIdOrderByCreatedAtDescIdDesc(ANA)).thenReturn(List.of(p));
        when(professionals.findById(RIVERA)).thenReturn(Optional.of(
                new Professional("MED-4471", "Dra. Rivera", "Clínica del Este", "x")));
    }

    @Test
    void cadaTipoDeEventoLlevaSuMarcaSuActorYLoQueHizo() {
        when(audit.findByPatientIdOrderByCreatedAtDescIdDesc(ANA)).thenReturn(List.of(
                fila(6, AuditEntry.Type.PERMISO_REVOCADO, RIVERA, 7L, List.of(Category.MEDICAMENTOS), null, 0),
                fila(5, AuditEntry.Type.SOLICITUD_AMPLIACION, RIVERA, 7L, List.of(Category.VACUNAS), "Pidió agregar vacunas", 2),
                fila(4, AuditEntry.Type.CONSULTA_DENEGADA, RIVERA, 7L, List.of(Category.VACUNAS), "Pidió vacunas, fuera del alcance", 3),
                fila(3, AuditEntry.Type.CONSULTA, RIVERA, 7L, List.of(Category.MEDICAMENTOS), null, 4),
                fila(2, AuditEntry.Type.PERMISO_CANJEADO, RIVERA, 7L, List.of(Category.MEDICAMENTOS), null, 5),
                fila(1, AuditEntry.Type.PERMISO_CREADO, null, 7L, List.of(Category.MEDICAMENTOS), null, 60 * 25)));

        List<Entrada> e = service.deLaPaciente(ANA);

        assertEquals(List.of(6L, 5L, 4L, 3L, 2L, 1L), e.stream().map(Entrada::id).toList());
        assertEquals(new Entrada(6L, Marca.PROPIA, "Tú", "Revocaste el permiso AFT-UN4 antes de que venciera",
                "ahora mismo"), e.get(0));
        assertEquals(new Entrada(5L, Marca.SOLICITUD, "Dra. Rivera · Clínica del Este",
                "Pidió agregar vacunas al código AFT-UN4", "hoy 8:53"), e.get(1));
        assertEquals(new Entrada(4L, Marca.DENEGADA, "Dra. Rivera · Clínica del Este",
                "Pidió vacunas, fuera del alcance · denegado", "hoy 8:52"), e.get(2));
        assertEquals(new Entrada(3L, Marca.CONSULTA, "Dra. Rivera · Clínica del Este",
                "Vio medicamentos y alergias", "hoy 8:51"), e.get(3));
        assertEquals(new Entrada(2L, Marca.CONSULTA, "Dra. Rivera · Clínica del Este",
                "Canjeó AFT-UN4 para ver medicamentos y alergias", "hoy 8:50"), e.get(4));
        assertEquals(new Entrada(1L, Marca.PROPIA, "Tú", "Generaste el código AFT-UN4 para medicamentos y alergias",
                "ayer 7:55"), e.get(5));
        // El profesional se busca una vez, aunque aparezca en cinco entradas.
        verify(professionals, times(1)).findById(RIVERA);
    }

    @Test
    void laEmergenciaSeDistingueYLlevaElMotivo() {
        when(audit.findByPatientIdOrderByCreatedAtDescIdDesc(ANA)).thenReturn(List.of(
                fila(9, AuditEntry.Type.EMERGENCIA, RIVERA, null, null, "paciente inconsciente tras caída", 10)));

        Entrada e = service.deLaPaciente(ANA).get(0);

        assertEquals(Marca.EMERGENCIA, e.marca());
        assertEquals("Acceso de emergencia", e.marca().getEtiqueta());
        assertEquals("Abrió tu ficha de emergencia · paciente inconsciente tras caída", e.que());
    }

    @Test
    void unDocumentoAbiertoDiceDeQueAlcanceEra() {
        when(audit.findByPatientIdOrderByCreatedAtDescIdDesc(ANA)).thenReturn(List.of(
                fila(8, AuditEntry.Type.CONSULTA, RIVERA, 7L, List.of(Category.MEDICAMENTOS), "Abrió un documento", 1)));

        assertEquals("Abrió un documento de medicamentos y alergias", service.deLaPaciente(ANA).get(0).que());
    }

    @Test
    void sinActividadLaListaEstaVaciaYSoloSeLeeLaDeLaPacienteDeLaSesion() {
        when(audit.findByPatientIdOrderByCreatedAtDescIdDesc(ANA)).thenReturn(List.of());

        assertTrue(service.deLaPaciente(ANA).isEmpty());
        verify(audit).findByPatientIdOrderByCreatedAtDescIdDesc(ANA);
        verify(permissions).findByPatientIdOrderByCreatedAtDescIdDesc(ANA);
    }

    private static AuditEntry fila(long id, AuditEntry.Type tipo, Long profesional, Long permiso,
                                   List<Category> categorias, String detalle, long minutosAntes) {
        AuditEntry e = new AuditEntry(ANA, tipo, profesional, permiso, categorias, detalle,
                LUNES.minus(Duration.ofMinutes(minutosAntes)));
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
