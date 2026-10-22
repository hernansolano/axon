package pa.axon.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import pa.axon.domain.CartillaService.ArchivoVista;
import pa.axon.domain.CartillaService.CartillaVista;
import pa.axon.domain.ConsultaService.Acceso;
import pa.axon.domain.ConsultaService.Resultado;
import pa.axon.domain.PermissionServiceTest.MovableClock;
import pa.axon.security.AccountType;
import pa.axon.security.LoginAttemptService;

/**
 * H4 (HU-08): el canje y la consulta del profesional. El código se normaliza, cada código que no
 * sirve da su resultado y deja su rastro (DEC-31, DEC-32), la consulta solo arma lo del alcance y lo
 * vuelve a validar en cada lectura, y lo fuera del alcance se deniega y se anota (CP-09, CP-10, CP-11).
 * Repositorios simulados y un reloj que se adelanta a mano.
 */
class ConsultaServiceTest {

    private static final long ANA = 1L;
    private static final long RIVERA = 1L;
    private static final long SOLIS = 2L;
    private static final Instant LUNES = Instant.parse("2026-10-13T14:00:00Z");

    PermissionRepository permissions = mock(PermissionRepository.class);
    AuditRepository audit = mock(AuditRepository.class);
    PatientRepository patients = mock(PatientRepository.class);
    CartillaService cartilla = mock(CartillaService.class);
    LoginAttemptService attempts = mock(LoginAttemptService.class);
    MovableClock clock = new MovableClock(LUNES);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ConsultaService service;
    Permission medicamentos;

    @BeforeEach
    void setUp() {
        service = new ConsultaService(permissions, audit, patients, cartilla, attempts, clock, jdbc);
        medicamentos = permiso(7L, "AFT-UN4", Set.of(Category.MEDICAMENTOS), Duration.ofHours(2));
        when(permissions.lockByCode("AFT-UN4")).thenReturn(Optional.of(medicamentos));
        when(patients.findById(ANA)).thenReturn(Optional.of(new Patient("8-912-2043", "Ana Cedeño", "x")));
        when(cartilla.autorizada(any(), any())).thenReturn(new CartillaVista(0, "0 registros", List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AFT-UN4", "aft-un4", "AFTUN4", "aft un4", " Aft-Un4 "})
    void elCanjeNoDistingueMayusculasNiExigeElGuion(String tecleado) {
        assertEquals(new ConsultaService.Canje(Resultado.CANJEADO, 7L), service.canjear(RIVERA, "MED-4471", tecleado, "10.0.0.2"));
    }

    @Test
    void alCanjearloQuedaAsociadoAlProfesionalYSeAnota() {
        service.canjear(RIVERA, "MED-4471", "AFT-UN4", "10.0.0.2");

        assertEquals(RIVERA, medicamentos.getProfessionalId());
        assertEquals(LUNES, medicamentos.getRedeemedAt());
        AuditEntry entry = unicaEntrada();
        assertEquals(AuditEntry.Type.PERMISO_CANJEADO, entry.getType());
        assertEquals(ANA, entry.getPatientId());
        assertEquals(RIVERA, entry.getProfessionalId());
        assertEquals(7L, entry.getPermissionId());
        verify(attempts, never()).recordRedeemFailure(any(), any(), any(), any());
    }

    @Test
    void elMismoProfesionalPuedeVolverACanjearSuCodigoSinOtraAnotacion() {
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        ReflectionTestUtils.setField(medicamentos, "redeemedAt", LUNES.minusSeconds(60));

        assertEquals(Resultado.CANJEADO, service.canjear(RIVERA, "MED-4471", "AFT-UN4", null).resultado());
        assertEquals(LUNES.minusSeconds(60), medicamentos.getRedeemedAt());
        verify(audit, never()).save(any());
    }

    @Test
    void unCodigoCanjeadoPorOtroProfesionalSeRechazaYSeAnota() {
        // HU-08, criterio 3, y DEC-31.
        ReflectionTestUtils.setField(medicamentos, "professionalId", SOLIS);

        assertEquals(Resultado.AJENO, service.canjear(RIVERA, "MED-4471", "AFT-UN4", "10.0.0.2").resultado());
        assertEquals(SOLIS, medicamentos.getProfessionalId());
        AuditEntry entry = unicaEntrada();
        assertEquals(AuditEntry.Type.CONSULTA_DENEGADA, entry.getType());
        assertEquals(RIVERA, entry.getProfessionalId());
        verify(attempts).recordRedeemFailure(AccountType.PROFESIONAL, "MED-4471", "10.0.0.2", "CANJE_AJENO");
    }

    @Test
    void unCodigoVencidoYUnoRevocadoDanResultadosDistintosYSeAnotan() {
        // HU-08, criterio 5: tres mensajes distintos para inválido, vencido y revocado.
        clock.set(LUNES.plus(Duration.ofHours(2)));
        assertEquals(Resultado.VENCIDO, service.canjear(RIVERA, "MED-4471", "AFT-UN4", null).resultado());

        clock.set(LUNES);
        ReflectionTestUtils.setField(medicamentos, "state", "REVOCADO");
        assertEquals(Resultado.REVOCADO, service.canjear(RIVERA, "MED-4471", "AFT-UN4", null).resultado());

        assertEquals(Resultado.INEXISTENTE, service.canjear(RIVERA, "MED-4471", "KKK-KKK", null).resultado());

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, times(2)).save(entries.capture());
        assertTrue(entries.getAllValues().stream().allMatch(e -> e.getType() == AuditEntry.Type.CONSULTA_DENEGADA));
        assertNull(medicamentos.getProfessionalId());
        verify(attempts).recordRedeemFailure(AccountType.PROFESIONAL, "MED-4471", null, "CANJE_VENCIDO");
        verify(attempts).recordRedeemFailure(AccountType.PROFESIONAL, "MED-4471", null, "CANJE_REVOCADO");
        verify(attempts).recordRedeemFailure(AccountType.PROFESIONAL, "MED-4471", null, "CANJE_INEXISTENTE");
        assertEquals(3, Set.of(Resultado.INEXISTENTE.getTitulo(), Resultado.VENCIDO.getTitulo(), Resultado.REVOCADO.getTitulo()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AFT-UN", "AFT-UN45", "OOO-III", "AF0-158", "<script>"})
    void loQueNoPuedeSerUnCodigoNiSeBuscaYCuentaComoInvalido(String tecleado) {
        assertEquals(Resultado.INEXISTENTE, service.canjear(RIVERA, "MED-4471", tecleado, null).resultado());
        verify(permissions, never()).lockByCode(anyString());
        verify(audit, never()).save(any());
    }

    @Test
    void vacioNoBuscaNiCuentaComoIntento() {
        assertEquals(Resultado.VACIO, service.canjear(RIVERA, "MED-4471", "  - ", null).resultado());
        assertEquals(Resultado.VACIO, service.canjear(RIVERA, "MED-4471", null, null).resultado());
        verify(attempts, never()).recordRedeemFailure(any(), any(), any(), any());
    }

    @Test
    void conDiezFallosEnQuinceMinutosNoCanjeaNiUnCodigoValido() {
        // DEC-32: el tope frena a quien prueba códigos al azar.
        when(attempts.recentFailures(AccountType.PROFESIONAL, "MED-4471", "CANJE_", LUNES.minus(Duration.ofMinutes(15))))
                .thenReturn(10L);
        assertEquals(Resultado.BLOQUEADO, service.canjear(RIVERA, "MED-4471", "AFT-UN4", null).resultado());
        assertNull(medicamentos.getProfessionalId());
        verify(permissions, never()).lockByCode(anyString());

        when(attempts.recentFailures(AccountType.PROFESIONAL, "MED-4471", "CANJE_", LUNES.minus(Duration.ofMinutes(15))))
                .thenReturn(9L);
        assertEquals(Resultado.CANJEADO, service.canjear(RIVERA, "MED-4471", "AFT-UN4", null).resultado());
    }

    @Test
    void laConsultaSoloArmaLoDelAlcanceYSeAnota() {
        // CP-09: el servicio pide a la cartilla solo las categorías del permiso.
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        clock.set(LUNES.plus(Duration.ofMinutes(1)));

        Acceso acceso = service.consulta(RIVERA, 7L).orElseThrow();

        assertTrue(acceso.vigente());
        verify(cartilla).autorizada(ANA, List.of(Category.MEDICAMENTOS));
        assertEquals("Ana Cedeño", acceso.vista().paciente());
        assertEquals("AC", acceso.vista().iniciales());
        assertEquals("Medicamentos y alergias", acceso.vista().alcanceTexto());
        assertEquals("1 h 59 min", acceso.vista().restante());
        assertEquals(List.of(Category.DIAGNOSTICOS, Category.LABORATORIOS, Category.VACUNAS, Category.OTROS), acceso.vista().fuera());
        assertEquals(AuditEntry.Type.CONSULTA, unicaEntrada().getType());
    }

    @Test
    void unaConsultaAjenaOInexistenteEsVacia() {
        // RS-03: tener cuenta no da nada; solo el permiso que canjeó este profesional.
        when(permissions.findByIdAndProfessionalId(7L, SOLIS)).thenReturn(Optional.empty());
        assertTrue(service.consulta(SOLIS, 7L).isEmpty());
        assertTrue(service.estado(SOLIS, 7L).isEmpty());
        assertTrue(service.denegar(SOLIS, 7L, Category.VACUNAS).isEmpty());
        assertTrue(service.solicitar(SOLIS, 7L, Category.VACUNAS).isEmpty());
        assertTrue(service.documento(SOLIS, 7L, UUID.randomUUID()).isEmpty());
        verify(cartilla, never()).autorizada(any(), any());
        verify(audit, never()).save(any());
    }

    @Test
    void conElPermisoRevocadoLaConsultaNoArmaLaCartillaYElEstadoLoDice() {
        // CP-11 y CR-15: el corte lo decide el servidor en cada lectura.
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        assertTrue(service.estado(RIVERA, 7L).orElseThrow().vigente());

        ReflectionTestUtils.setField(medicamentos, "state", "REVOCADO");
        ConsultaService.Estado estado = service.estado(RIVERA, 7L).orElseThrow();
        assertFalse(estado.vigente());
        assertEquals("revocado", estado.corte().getSlug());
        assertEquals(0, estado.restanteSeg());

        Acceso acceso = service.consulta(RIVERA, 7L).orElseThrow();
        assertFalse(acceso.vigente());
        assertEquals(Resultado.REVOCADO, acceso.corte());
        verify(cartilla, never()).autorizada(any(), any());
        assertEquals(AuditEntry.Type.CONSULTA_DENEGADA, unicaEntrada().getType());
    }

    @Test
    void alVencerElEstadoDiceVencidoSinQueNadaLoEscriba() {
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        clock.set(LUNES.plus(Duration.ofHours(2)));
        assertEquals(Resultado.VENCIDO, service.estado(RIVERA, 7L).orElseThrow().corte());
        verify(permissions, never()).save(any());
        verify(audit, never()).save(any());
    }

    @Test
    void unaCategoriaFueraDelAlcanceSeDeniegaSeExplicaYSeAnota() {
        // CP-10 y HU-08, criterios 6 y 7.
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));

        ConsultaService.Pedido pedido = service.denegar(RIVERA, 7L, Category.VACUNAS).orElseThrow();

        assertEquals(Category.VACUNAS, pedido.categoria());
        assertEquals("Medicamentos y alergias", pedido.autorizado());
        AuditEntry entry = unicaEntrada();
        assertEquals(AuditEntry.Type.CONSULTA_DENEGADA, entry.getType());
        assertEquals(List.of("VACUNAS"), List.of(entry.getCategories()));
        assertEquals("Pidió vacunas, fuera del alcance", entry.getDetail());
    }

    @Test
    void unaCategoriaDentroDelAlcanceNoSeDeniega() {
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        assertTrue(service.denegar(RIVERA, 7L, Category.MEDICAMENTOS).isEmpty());
        verify(audit, never()).save(any());
    }

    @Test
    void laSolicitudDeAmpliacionQuedaEnLaBitacoraYNoCambiaElPermiso() {
        // PB-19 (DEC-33).
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));

        assertTrue(service.solicitar(RIVERA, 7L, Category.VACUNAS).isPresent());
        AuditEntry entry = unicaEntrada();
        assertEquals(AuditEntry.Type.SOLICITUD_AMPLIACION, entry.getType());
        assertEquals("Pidió agregar vacunas", entry.getDetail());
        assertEquals(List.of(Category.MEDICAMENTOS), medicamentos.getScope());
    }

    @Test
    void elDocumentoSoloSaleConElPermisoVigenteYDentroDelAlcance() {
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        UUID receta = UUID.randomUUID();
        when(cartilla.documentoAutorizado(ANA, List.of(Category.MEDICAMENTOS), receta))
                .thenReturn(Optional.of(new ArchivoVista("application/pdf", new byte[] {1})));

        assertTrue(service.documento(RIVERA, 7L, receta).isPresent());
        assertEquals(AuditEntry.Type.CONSULTA, unicaEntrada().getType());

        clock.set(LUNES.plus(Duration.ofHours(3)));
        assertTrue(service.documento(RIVERA, 7L, receta).isEmpty());
        verify(cartilla, times(1)).documentoAutorizado(eq(ANA), any(), eq(receta));
    }

    @Test
    void unCodigoAjenoRevocadoDiceAjenoYNoCuentaSuEstado() {
        // Verificador (H4): el estado de un permiso que no es suyo no se le revela.
        ReflectionTestUtils.setField(medicamentos, "professionalId", SOLIS);
        ReflectionTestUtils.setField(medicamentos, "state", "REVOCADO");
        assertEquals(Resultado.AJENO, service.canjear(RIVERA, "MED-4471", "AFT-UN4", null).resultado());
    }

    @Test
    void unDocumentoNegadoQuedaEnLaBitacora() {
        // Guardián (H4): con el permiso cortado o fuera del alcance, la paciente se entera.
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        UUID ajeno = UUID.randomUUID();
        when(cartilla.documentoAutorizado(ANA, List.of(Category.MEDICAMENTOS), ajeno)).thenReturn(Optional.empty());

        assertTrue(service.documento(RIVERA, 7L, ajeno).isEmpty());
        AuditEntry fuera = unicaEntrada();
        assertEquals(AuditEntry.Type.CONSULTA_DENEGADA, fuera.getType());
        assertEquals("Intentó abrir un documento fuera del permiso", fuera.getDetail());

        ReflectionTestUtils.setField(medicamentos, "state", "REVOCADO");
        assertTrue(service.documento(RIVERA, 7L, ajeno).isEmpty());
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, times(2)).save(entries.capture());
        assertEquals("Intentó abrir un documento con el permiso revocado", entries.getAllValues().get(1).getDetail());
    }

    @Test
    void unDocumentoDeUnTipoQueNoSeSirveSeAnotaComoDenegado() {
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        UUID html = UUID.randomUUID();
        when(cartilla.documentoAutorizado(ANA, List.of(Category.MEDICAMENTOS), html))
                .thenReturn(Optional.of(new ArchivoVista("text/html", new byte[] {60})));
        assertTrue(service.documento(RIVERA, 7L, html).isEmpty());
        assertEquals(AuditEntry.Type.CONSULTA_DENEGADA, unicaEntrada().getType());
    }

    @Test
    void laSolicitudSeAnotaUnaSolaVezPorPermisoYCategoria() {
        ReflectionTestUtils.setField(medicamentos, "professionalId", RIVERA);
        when(permissions.findByIdAndProfessionalId(7L, RIVERA)).thenReturn(Optional.of(medicamentos));
        when(audit.alreadyRequested(7L, RIVERA, "VACUNAS")).thenReturn(true);

        assertEquals("Medicamentos y alergias", service.solicitar(RIVERA, 7L, Category.VACUNAS).orElseThrow().autorizado());
        verify(audit, never()).save(any());
    }

    @Test
    void cadaCanjeTomaElBloqueoDelProfesionalAntesDeContarLosFallos() {
        // Guardián (H4): una ráfaga en paralelo no puede saltarse el tope.
        service.canjear(RIVERA, "MED-4471", "AFT-UN4", null);
        verify(jdbc).queryForList("select pg_advisory_xact_lock(?)", RIVERA);
    }

    @Test
    void siNoSeGuardaElIntentoElCanjeFallaCerradoYNoAbreNada() {
        doThrow(new IllegalStateException("base caída"))
                .when(attempts).recordRedeemFailure(any(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> service.canjear(RIVERA, "MED-4471", "KKK-KKK", null));
    }

    @Test
    void normalizarSoloAceptaElAlfabetoDeLaEspecificacion() {
        assertEquals(Optional.of("AFT-UN4"), ConsultaService.normalizar("AFTUN4"));
        assertTrue(ConsultaService.normalizar("AFTUN0").isEmpty());
        assertTrue(ConsultaService.normalizar("AFTUN").isEmpty());
        verify(permissions, never()).findByIdAndProfessionalId(anyLong(), anyLong());
    }

    private AuditEntry unicaEntrada() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(entry.capture());
        return entry.getValue();
    }

    private Permission permiso(Long id, String code, Set<Category> scope, Duration vigencia) {
        Permission p = new Permission(ANA, code, scope, "Entrega de resultados", LUNES, LUNES.plus(vigencia));
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }
}
