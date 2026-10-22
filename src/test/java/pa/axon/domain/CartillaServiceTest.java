package pa.axon.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pa.axon.domain.CartillaService.NuevoRegistro;

/**
 * H2: el detalle y los documentos solo salen para su dueña (CP-02, CP-15), la procedencia se deriva
 * del documento y un archivo borrado del disco no se lleva el registro. H7: un registro nuevo queda
 * con documento o autodeclarado, y su archivo se guarda por UUID (HU-03). Repositorios simulados y un
 * almacén real en una carpeta temporal.
 */
class CartillaServiceTest {

    private static final long ANA = 1L;
    private static final long OTRA = 2L;

    @TempDir
    Path almacen;

    RecordRepository records = mock(RecordRepository.class);
    DocumentRepository documents = mock(DocumentRepository.class);
    DocumentStorage storage;
    CartillaService cartilla;

    Record losartan = record(10L, ANA, Category.MEDICAMENTOS, "Losartán 50 mg", LocalDate.of(2026, 1, 12), "Clínica del Este");
    Record lumbar = record(11L, ANA, Category.DIAGNOSTICOS, "Dolor lumbar recurrente", LocalDate.of(2026, 5, 6), null);
    UUID receta = UUID.randomUUID();
    Document recetaPdf = new Document(10L, "receta-losartan.pdf", "application/pdf", receta, 4);

    @BeforeEach
    void datos() {
        storage = new DocumentStorage(almacen);
        cartilla = new CartillaService(records, documents, storage);
        when(records.findByIdAndPatientId(10L, ANA)).thenReturn(Optional.of(losartan));
        when(records.findByIdAndPatientId(11L, ANA)).thenReturn(Optional.of(lumbar));
        when(documents.findByRecordId(10L)).thenReturn(Optional.of(recetaPdf));
        when(documents.findByRecordId(11L)).thenReturn(Optional.empty());
        // La consulta real une documento con registro y filtra por dueña; aquí, solo Ana la tiene.
        when(documents.findOwned(receta, ANA)).thenReturn(Optional.of(recetaPdf));
        storage.save(receta, new byte[] {1, 2, 3, 4});
    }

    @Test
    void elDetalleDeUnRegistroAjenoEsIgualAlDeUnoQueNoExiste() {
        // CP-02: el repositorio filtra por dueña; para otra paciente el registro 10 no existe.
        assertTrue(cartilla.detalle(OTRA, 10L).isEmpty());
        assertTrue(cartilla.detalle(ANA, 999L).isEmpty());
    }

    @Test
    void elDetalleConDocumentoDiceDeQueTipoEsYDondeEsta() {
        var detalle = cartilla.detalle(ANA, 10L).orElseThrow();
        assertTrue(detalle.conDocumento());
        assertTrue(detalle.esPdf());
        assertEquals(receta, detalle.documentoId());
        assertEquals("Con documento · PDF", detalle.procedencia());
        assertEquals("Enero 2026", detalle.fecha());
        assertEquals("Clínica del Este", detalle.institucion());
    }

    @Test
    void sinDocumentoEsAutodeclaradoYSinInstitucionLoDice() {
        var detalle = cartilla.detalle(ANA, 11L).orElseThrow();
        assertFalse(detalle.conDocumento());
        assertNull(detalle.documentoId());
        assertEquals("Lo dijo la paciente, sin documento", detalle.procedencia());
        assertEquals("Sin institución anotada", detalle.institucion());
    }

    @Test
    void laDuenaRecibeSuDocumento() {
        var archivo = cartilla.documento(ANA, receta).orElseThrow();
        assertEquals("application/pdf", archivo.tipoMime());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, archivo.contenido());
    }

    @Test
    void unDocumentoAdivinadoNoExiste() {
        // CP-15: ningún documento tiene ese UUID.
        assertTrue(cartilla.documento(ANA, UUID.randomUUID()).isEmpty());
    }

    @Test
    void elDocumentoDeOtraPacienteNoSaleAunqueSeConozcaSuDireccion() {
        assertTrue(cartilla.documento(OTRA, receta).isEmpty());
    }

    @Test
    void siElArchivoSeBorroDelDiscoElDocumentoNoSaleYElRegistroSigue() throws Exception {
        Files.delete(almacen.resolve(receta.toString()));
        assertTrue(cartilla.documento(ANA, receta).isEmpty());
        assertTrue(cartilla.detalle(ANA, 10L).orElseThrow().conDocumento());
    }

    @Test
    void laCartillaSeAgrupaEnElOrdenDeLasCategoriasYConservaLaFechaDescendente() {
        // CP-06: el repositorio entrega por fecha descendente; los grupos siguen el orden del enum.
        Record penicilina = record(12L, ANA, Category.MEDICAMENTOS, "Penicilina, alergia", LocalDate.of(2024, 3, 20), "Centro de Salud de Chilibre");
        Record control = record(13L, ANA, Category.DIAGNOSTICOS, "Control prenatal, semana 24", LocalDate.of(2026, 8, 14), "Policlínica Villa Esperanza");
        when(records.findByPatientIdOrderByDocumentDateDescIdDesc(ANA)).thenReturn(List.of(control, lumbar, losartan, penicilina));
        when(documents.findByRecordIdIn(anyCollection())).thenReturn(List.of(recetaPdf));

        var vista = cartilla.deLaPaciente(ANA);

        assertEquals(4, vista.total());
        assertEquals(List.of(Category.DIAGNOSTICOS, Category.MEDICAMENTOS),
                vista.grupos().stream().map(CartillaService.GrupoVista::categoria).toList());
        assertEquals(List.of("Control prenatal, semana 24", "Dolor lumbar recurrente"),
                vista.grupos().get(0).registros().stream().map(CartillaService.RegistroVista::titulo).toList());
        assertEquals(List.of("Losartán 50 mg", "Penicilina, alergia"),
                vista.grupos().get(1).registros().stream().map(CartillaService.RegistroVista::titulo).toList());
        assertEquals("mayo 2026 · Sin institución anotada", vista.grupos().get(0).registros().get(1).meta());
        assertTrue(vista.grupos().get(1).registros().get(0).conDocumento());
        assertFalse(vista.grupos().get(1).registros().get(1).conDocumento());
    }

    @Test
    void unRegistroSinDocumentoQuedaAutodeclaradoYEsDeLaPacienteDeLaSesion() {
        // HU-03 §3 y CP-05: sin documento no hay fila en documento, y de eso se deriva la procedencia.
        ArgumentCaptor<Record> captor = alGuardarRecibeElId(20L);

        Long id = cartilla.agregar(ANA, new NuevoRegistro(Category.VACUNAS, "Influenza · dosis anual",
                LocalDate.of(2026, 4, 2), "  "), null);

        Record guardado = captor.getValue();
        assertEquals(20L, id);
        assertEquals(ANA, guardado.getPatientId());
        assertEquals(Category.VACUNAS, guardado.getCategory());
        assertEquals("Influenza · dosis anual", guardado.getTitle());
        assertEquals(LocalDate.of(2026, 4, 2), guardado.getDocumentDate());
        assertNull(guardado.getOriginInstitution(), "una institución en blanco queda sin anotar");
        verify(documents, never()).save(any());
    }

    @Test
    void conDocumentoGuardaElArchivoPorSuUuidYNoPorElNombreQueMandoElNavegador() throws Exception {
        // HU-03 §1 y CP-04: el archivo queda asociado al registro, fuera de lo público y sin dirección adivinable.
        alGuardarRecibeElId(21L);
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

        cartilla.agregar(ANA, new NuevoRegistro(Category.VACUNAS, "Influenza · dosis anual",
                LocalDate.of(2026, 4, 2), "Minsa"), DocumentUpload.of("carne.jpg", jpg).orElseThrow());

        ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
        verify(documents).save(doc.capture());
        assertEquals(21L, doc.getValue().getRecordId());
        assertEquals("image/jpeg", doc.getValue().getMimeType());
        assertEquals("carne.jpg", doc.getValue().getOriginalName());
        assertArrayEquals(jpg, Files.readAllBytes(almacen.resolve(doc.getValue().getStoragePath().toString())));
        assertFalse(Files.exists(almacen.resolve("carne.jpg")));
    }

    @Test
    void siLaTransaccionNoSeConfirmaElArchivoNoQuedaEnElDisco() throws Exception {
        alGuardarRecibeElId(22L);
        byte[] pdf = "%PDF-1.4 sintetico".getBytes();
        TransactionSynchronizationManager.initSynchronization();
        try {
            cartilla.agregar(ANA, new NuevoRegistro(Category.LABORATORIOS, "Hemograma", LocalDate.of(2026, 2, 1), null),
                    DocumentUpload.of("hemograma.pdf", pdf).orElseThrow());
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        // Solo queda el archivo sembrado en datos().
        try (Stream<Path> archivos = Files.list(almacen)) {
            assertEquals(List.of(almacen.resolve(receta.toString())), archivos.toList());
        }
    }

    @Test
    void siNoSeSabeComoTerminoElCommitElArchivoSeQueda() throws Exception {
        // Guardián: con STATUS_UNKNOWN la base pudo confirmar; borrar dejaría «con documento» sin archivo.
        alGuardarRecibeElId(23L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cartilla.agregar(ANA, new NuevoRegistro(Category.LABORATORIOS, "Hemograma", LocalDate.of(2026, 2, 1), null),
                    DocumentUpload.of("hemograma.pdf", "%PDF-1.4 sintetico".getBytes()).orElseThrow());
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        ArgumentCaptor<Document> doc = ArgumentCaptor.forClass(Document.class);
        verify(documents).save(doc.capture());
        assertTrue(Files.exists(almacen.resolve(doc.getValue().getStoragePath().toString())));
    }

    /** El repositorio simulado asigna el id como lo haría la base, y guarda lo que recibió. */
    private ArgumentCaptor<Record> alGuardarRecibeElId(Long id) {
        ArgumentCaptor<Record> captor = ArgumentCaptor.forClass(Record.class);
        when(records.save(captor.capture())).thenAnswer(inv -> {
            Record r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", id);
            return r;
        });
        return captor;
    }

    @Test
    void laVistaDelProfesionalPideALaBaseSoloLasCategoriasDelAlcanceYLlevaElIdDelDocumento() {
        // H4 (HU-08, RS-04): el filtro va en la consulta; lo de fuera del alcance ni se lee.
        when(records.findByPatientIdAndCategoryInOrderByDocumentDateDescIdDesc(ANA, List.of(Category.MEDICAMENTOS)))
                .thenReturn(List.of(losartan));
        when(documents.findByRecordIdIn(List.of(10L))).thenReturn(List.of(recetaPdf));

        var vista = cartilla.autorizada(ANA, List.of(Category.MEDICAMENTOS));

        assertEquals(1, vista.grupos().size());
        assertEquals(Category.MEDICAMENTOS, vista.grupos().get(0).categoria());
        assertEquals(receta, vista.grupos().get(0).registros().get(0).documentoId());
        verify(records, never()).findByPatientIdOrderByDocumentDateDescIdDesc(any());
    }

    @Test
    void laCartillaDeLaPacienteNoExponeElIdDelDocumento() {
        when(records.findByPatientIdOrderByDocumentDateDescIdDesc(ANA)).thenReturn(List.of(losartan));
        when(documents.findByRecordIdIn(List.of(10L))).thenReturn(List.of(recetaPdf));
        assertNull(cartilla.deLaPaciente(ANA).grupos().get(0).registros().get(0).documentoId());
    }

    @Test
    void unDocumentoFueraDelAlcanceNoSaleParaElProfesional() {
        when(documents.findInScope(receta, ANA, List.of(Category.VACUNAS))).thenReturn(Optional.empty());
        assertTrue(cartilla.documentoAutorizado(ANA, List.of(Category.VACUNAS), receta).isEmpty());
        assertTrue(cartilla.documentoAutorizado(ANA, List.of(), receta).isEmpty());
        when(documents.findInScope(receta, ANA, List.of(Category.MEDICAMENTOS))).thenReturn(Optional.of(recetaPdf));
        assertArrayEquals(new byte[] {1, 2, 3, 4},
                cartilla.documentoAutorizado(ANA, List.of(Category.MEDICAMENTOS), receta).orElseThrow().contenido());
    }

    private static Record record(Long id, Long patientId, Category category, String title, LocalDate date, String institution) {
        Record record = new Record(patientId, category, title, null, date, institution);
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }
}
