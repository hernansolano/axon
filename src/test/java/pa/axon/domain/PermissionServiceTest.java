package pa.axon.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import pa.axon.config.ClockConfig;
import pa.axon.domain.PermissionService.NuevoPermiso;
import pa.axon.domain.PermissionService.PermisoVista;

/**
 * H3: crear un permiso con alcance y vigencia (HU-07, CP-08), su estado calculado con la hora sin tocar
 * la base (ESPEC §2, DEC-28) y la revocación con efecto inmediato (HU-09, CP-11). Repositorios
 * simulados y un reloj que se adelanta a mano.
 */
class PermissionServiceTest {

    private static final long ANA = 1L;
    private static final long OTRA = 2L;
    private static final ZoneId PANAMA = ZoneId.of("America/Panama");
    /** Lunes 12 de octubre de 2026, 8:55 en Panamá. */
    private static final Instant LUNES = Instant.parse("2026-10-12T13:55:00Z");

    PermissionRepository permissions = mock(PermissionRepository.class);
    AuditRepository audit = mock(AuditRepository.class);
    RecordRepository records = mock(RecordRepository.class);
    ProfessionalRepository professionals = mock(ProfessionalRepository.class);
    CodigoGenerator codes = mock(CodigoGenerator.class);
    MovableClock clock = new MovableClock(LUNES);
    PermissionService service;

    @BeforeEach
    void setUp() {
        service = new PermissionService(permissions, audit, records, professionals, codes, clock);
        when(codes.next()).thenReturn("AFT-UN4");
        when(permissions.save(any())).thenAnswer(inv -> {
            Permission p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 7L);
            return p;
        });
    }

    @Test
    void creaElPermisoConElAlcanceYLaVigenciaElegidosYLoAnotaEnLaBitacora() {
        Long id = service.crear(ANA, new NuevoPermiso(Set.of(Category.VACUNAS, Category.MEDICAMENTOS),
                ShareDuration.HORAS_2, "Consulta general"));

        assertEquals(7L, id);
        ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
        verify(permissions).save(saved.capture());
        Permission p = saved.getValue();
        assertEquals(ANA, p.getPatientId());
        assertEquals("AFT-UN4", p.getCode());
        assertEquals(List.of(Category.MEDICAMENTOS, Category.VACUNAS), p.getScope());
        assertEquals("Consulta general", p.getPurpose());
        assertEquals(LUNES, p.getCreatedAt());
        assertEquals(LUNES.plus(Duration.ofHours(2)), p.getExpiresAt());
        assertEquals(Permission.Status.ACTIVO, p.status(LUNES));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertEquals(AuditEntry.Type.PERMISO_CREADO, entry.getValue().getType());
        assertEquals(ANA, entry.getValue().getPatientId());
        assertEquals(7L, entry.getValue().getPermissionId());
        assertArrayEquals(new String[] {"MEDICAMENTOS", "VACUNAS"}, entry.getValue().getCategories());
        assertEquals(LUNES, entry.getValue().getCreatedAt());
    }

    @Test
    void lasTresVigenciasVencenALos15MinutosDosHorasYUnDia() {
        for (ShareDuration d : ShareDuration.values()) {
            service.crear(ANA, new NuevoPermiso(Set.of(Category.OTROS), d, "Control prenatal"));
        }
        ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, times(3)).save(saved.capture());
        assertEquals(List.of(Duration.ofMinutes(15), Duration.ofHours(2), Duration.ofHours(24)),
                saved.getAllValues().stream().map(p -> Duration.between(p.getCreatedAt(), p.getExpiresAt())).toList());
    }

    @Test
    void siElCodigoYaExisteGeneraOtro() {
        when(codes.next()).thenReturn("AFT-UN4", "K2P-77M");
        when(permissions.existsByCode("AFT-UN4")).thenReturn(true);
        service.crear(ANA, new NuevoPermiso(Set.of(Category.VACUNAS), ShareDuration.MIN_15, "Consulta general"));
        ArgumentCaptor<Permission> saved = ArgumentCaptor.forClass(Permission.class);
        verify(permissions).save(saved.capture());
        assertEquals("K2P-77M", saved.getValue().getCode());
    }

    @Test
    void sinCategoriasOConUnPropositoInventadoNoCreaNada() {
        assertThrows(IllegalArgumentException.class, () -> service.crear(ANA,
                new NuevoPermiso(Set.of(), ShareDuration.HORAS_2, "Consulta general")));
        assertThrows(IllegalArgumentException.class, () -> service.crear(ANA,
                new NuevoPermiso(Set.of(Category.VACUNAS), ShareDuration.HORAS_2, "Ver todo")));
        // Sin propósito es un rechazo como los demás, no un NullPointerException.
        assertThrows(IllegalArgumentException.class, () -> service.crear(ANA,
                new NuevoPermiso(Set.of(Category.VACUNAS), ShareDuration.HORAS_2, null)));
        verify(permissions, never()).save(any());
        verify(audit, never()).save(any());
    }

    @Test
    void venceSoloConLaHoraSinQueNadaLoEscribaEnLaBase() {
        // HU-07, criterio 6, y ESPEC §2: el estado cambia porque pasa el tiempo, no porque alguien lo guarde.
        Permission p = permiso(7L, ANA, LUNES, Duration.ofMinutes(15));
        when(permissions.findByPatientIdOrderByCreatedAtDescIdDesc(ANA)).thenReturn(List.of(p));

        clock.set(LUNES.plus(Duration.ofMinutes(14)));
        PermisoVista antes = service.deLaPaciente(ANA).get(0);
        assertTrue(antes.activo());
        assertEquals(60, antes.restanteSeg());
        assertEquals("01:00", antes.restante());

        clock.set(LUNES.plus(Duration.ofMinutes(15)));
        PermisoVista despues = service.deLaPaciente(ANA).get(0);
        assertTrue(despues.vencido());
        assertEquals(0, despues.restanteSeg());
        verify(permissions, never()).save(any());
        verify(permissions, never()).revoke(anyLong(), anyLong(), any());
    }

    @Test
    void laVistaDiceQuienCanjeoElAlcanceLoQueQuedaFueraYCuandoSeGenero() {
        Permission sinCanjear = permiso(7L, ANA, LUNES, Duration.ofHours(2));
        Permission canjeado = permiso(8L, ANA, LUNES.minus(Duration.ofDays(1)).plus(Duration.ofHours(7).plusMinutes(15)), Duration.ofHours(24));
        ReflectionTestUtils.setField(canjeado, "professionalId", 3L);
        ReflectionTestUtils.setField(canjeado, "scope", new String[] {"LABORATORIOS"});
        when(professionals.findById(3L)).thenReturn(Optional.of(new Professional("MED-2210", "Dr. Solís", "Laboratorio Santa Ana", "x")));
        when(permissions.findByPatientIdOrderByCreatedAtDescIdDesc(ANA)).thenReturn(List.of(sinCanjear, canjeado));

        clock.set(LUNES.plus(Duration.ofMinutes(1)));
        List<PermisoVista> vistas = service.deLaPaciente(ANA);

        PermisoVista a = vistas.get(0);
        assertEquals("Sin canjear", a.quien());
        assertEquals("Medicamentos y alergias", a.alcanceTexto());
        assertEquals("Alcance: medicamentos y alergias.", a.alcanceFrase());
        assertEquals("Queda fuera: diagnósticos y antecedentes, laboratorios y estudios, vacunas y otros documentos.", a.quedaFuera());
        assertEquals("Consulta general · generado hoy 8:55", a.meta());
        assertEquals("1 h 59 min", a.restante());
        assertEquals("AFT · UN4", a.deletreado());
        assertEquals(List.of("A", "F", "T", "-", "U", "N", "4"), a.caracteres());

        PermisoVista b = vistas.get(1);
        assertEquals("Dr. Solís · Laboratorio Santa Ana", b.quien());
        assertEquals("Consulta general · generado ayer 16:10", b.meta());
    }

    @Test
    void unPermisoAjenoNoSeVeNiSeRevoca() {
        when(permissions.findByIdAndPatientId(7L, OTRA)).thenReturn(Optional.empty());
        when(permissions.lockOwned(7L, OTRA)).thenReturn(Optional.empty());
        assertTrue(service.uno(OTRA, 7L).isEmpty());
        assertTrue(service.revocar(OTRA, 7L).isEmpty());
        verify(permissions, never()).revoke(anyLong(), anyLong(), any());
        verify(audit, never()).save(any());
    }

    @Test
    void revocarCortaDeInmediatoYDejaLaRevocacionEnLaBitacora() {
        // CP-11: la revocación es una sentencia que solo cambia un permiso vigente de la paciente.
        Permission p = permiso(7L, ANA, LUNES, Duration.ofHours(2));
        when(permissions.lockOwned(7L, ANA)).thenReturn(Optional.of(p));
        Instant ahora = LUNES.plus(Duration.ofMinutes(5));
        clock.set(ahora);
        when(permissions.revoke(7L, ANA, ahora)).thenReturn(1);

        String aviso = service.revocar(ANA, 7L).orElseThrow();

        assertEquals("El código AFT-UN4 ya no sirve. Lo que vio antes sigue anotado en tu actividad: "
                + "revocar no borra el historial.", aviso);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertEquals(AuditEntry.Type.PERMISO_REVOCADO, entry.getValue().getType());
        assertEquals(7L, entry.getValue().getPermissionId());
        assertEquals(ahora, entry.getValue().getCreatedAt());
    }

    @Test
    void conElPermisoCanjeadoElAvisoNombraAlProfesional() {
        Permission p = permiso(7L, ANA, LUNES, Duration.ofHours(2));
        ReflectionTestUtils.setField(p, "professionalId", 3L);
        when(professionals.findById(3L)).thenReturn(Optional.of(new Professional("MED-4471", "Dra. Rivera", "Clínica del Este", "x")));
        when(permissions.lockOwned(7L, ANA)).thenReturn(Optional.of(p));
        when(permissions.revoke(7L, ANA, LUNES)).thenReturn(1);

        assertTrue(service.revocar(ANA, 7L).orElseThrow().startsWith("Dra. Rivera · Clínica del Este ya no puede ver nada."));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        assertEquals(3L, entry.getValue().getProfessionalId());
    }

    @Test
    void unoVencidoOYaRevocadoNoSeVuelveARevocarNiSeAnotaDosVeces() {
        Permission p = permiso(7L, ANA, LUNES, Duration.ofMinutes(15));
        when(permissions.lockOwned(7L, ANA)).thenReturn(Optional.of(p));
        when(permissions.revoke(anyLong(), anyLong(), any())).thenReturn(0);

        assertEquals(Optional.of(""), service.revocar(ANA, 7L));
        verify(audit, never()).save(any());
    }

    @Test
    void revocadoGanaAlVencimiento() {
        Permission p = permiso(7L, ANA, LUNES, Duration.ofMinutes(15));
        ReflectionTestUtils.setField(p, "state", "REVOCADO");
        assertEquals(Permission.Status.REVOCADO, p.status(LUNES));
        assertEquals(Permission.Status.REVOCADO, p.status(LUNES.plus(Duration.ofDays(1))));
    }

    @Test
    void lasOpcionesDicenCuantosRegistrosHayEnCadaCategoria() {
        RecordRepository.CategoryCount dos = conteo(Category.MEDICAMENTOS, 2);
        RecordRepository.CategoryCount uno = conteo(Category.VACUNAS, 1);
        when(records.countByCategory(ANA)).thenReturn(List.of(dos, uno));
        assertEquals(List.of("Nada guardado", "2 registros", "Nada guardado", "1 registro", "Nada guardado"),
                service.opciones(ANA).stream().map(PermissionService.Opcion::conteo).toList());
    }

    @Test
    void elResumenDiceQueQuedaFueraYCuandoNoQuedaNada() {
        assertEquals("Diagnósticos y antecedentes, laboratorios y estudios, vacunas y otros documentos",
                PermissionService.resumen(Set.of(Category.MEDICAMENTOS)).quedaFuera());
        assertEquals("El médico no lo ve ni sabe que existe.", PermissionService.resumen(Set.of(Category.MEDICAMENTOS)).nota());
        assertEquals("Nada. Le muestras la cartilla completa.", PermissionService.resumen(Set.of(Category.values())).quedaFuera());
    }

    @Test
    void elContadorUsaElFormatoDelDiseno() {
        assertEquals("vencido", PermissionService.restante(0));
        assertEquals("00:01", PermissionService.restante(1));
        assertEquals("37:18", PermissionService.restante(37 * 60 + 18));
        assertEquals("1 h 00 min", PermissionService.restante(3600));
        assertEquals("23 h 59 min", PermissionService.restante(24 * 3600 - 1));
        assertFalse(PermissionService.restante(59).contains("h"));
    }

    @Test
    void elRelojDeLaAplicacionEsElDePanama() {
        // UTC-5 todo el año: «hoy 8:55» de las pruebas es la hora de Panamá.
        assertEquals(PANAMA, new ClockConfig().clock().getZone());
        assertEquals(ZoneOffset.ofHours(-5), PANAMA.getRules().getOffset(LUNES));
    }

    private static Permission permiso(Long id, Long patientId, Instant creado, Duration vigencia) {
        Permission p = new Permission(patientId, "AFT-UN4", Set.of(Category.MEDICAMENTOS), "Consulta general",
                creado, creado.plus(vigencia));
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private static RecordRepository.CategoryCount conteo(Category c, long n) {
        return new RecordRepository.CategoryCount() {
            @Override
            public Category getCategory() {
                return c;
            }

            @Override
            public long getTotal() {
                return n;
            }
        };
    }

    /** Un reloj fijo que la prueba adelanta. */
    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return PANAMA;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
