package pa.axon.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import pa.axon.domain.Category;
import pa.axon.domain.Document;
import pa.axon.domain.DocumentRepository;
import pa.axon.domain.DocumentStorage;
import pa.axon.domain.Patient;
import pa.axon.domain.PatientRepository;
import pa.axon.domain.Professional;
import pa.axon.domain.ProfessionalRepository;
import pa.axon.domain.Record;
import pa.axon.domain.RecordRepository;

class DevDataSeederTest {

    @Test
    void siembraSoloEnUnaBaseDeEstaMaquina() {
        assertTrue(DevDataSeeder.isLocal("jdbc:postgresql://localhost:5432/axon"));
        assertTrue(DevDataSeeder.isLocal("jdbc:postgresql://127.0.0.1/axon"));
        assertTrue(DevDataSeeder.isLocal("jdbc:postgresql://[::1]:5432/axon"));
    }

    @Test
    void noSiembraContraUnaBaseRemotaNiContraUnNombreEngañoso() {
        assertFalse(DevDataSeeder.isLocal("jdbc:postgresql://db.ejemplo.com:5432/axon"));
        assertFalse(DevDataSeeder.isLocal("jdbc:postgresql://localhost.ejemplo.com/axon"));
        assertFalse(DevDataSeeder.isLocal("jdbc:postgresql://10.0.0.5:5432/axon"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"carne-perinatal.jpg", "nota-alergia.jpg", "carne-vacunas.jpg"})
    void cadaImagenDeEjemploExisteYEsUnJpeg(String fileName) throws IOException {
        byte[] content = DevDataSeeder.sample(fileName);
        assertTrue(content.length > 0, "falta semilla/" + fileName);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(content)), fileName + " no es una imagen");
    }

    @ParameterizedTest
    @ValueSource(strings = {"receta-losartan.pdf", "hemograma.pdf"})
    void cadaPdfDeEjemploExisteYLlevaLaMarcaSintetico(String fileName) {
        byte[] content = DevDataSeeder.sample(fileName);
        String text = new String(content, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-"), fileName + " no es un PDF");
        // Los PDF usan WinAnsiEncoding: la É es el byte 0xC9.
        assertTrue(text.contains("SINTÉTICO"), fileName + " no lleva la marca");
    }

    @Test
    void unNombreDeLaBaseNoSacaNadaFueraDeSemilla() {
        assertEquals(0, DevDataSeeder.sample("../application-dev.yml").length);
        assertEquals(0, DevDataSeeder.sample("../db/migration/V1__esquema_inicial.sql").length);
        assertEquals(0, DevDataSeeder.sample("subido-por-ana.pdf").length);
    }

    @Test
    void enUnaBaseVaciaSiembraElConjuntoDeEspec8() {
        PatientRepository patients = mock(PatientRepository.class);
        ProfessionalRepository professionals = mock(ProfessionalRepository.class);
        RecordRepository records = mock(RecordRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentStorage storage = mock(DocumentStorage.class);
        when(patients.save(any())).thenAnswer(i -> i.getArgument(0));
        when(records.save(any())).thenAnswer(i -> i.getArgument(0));

        new DevDataSeeder(patients, professionals, records, documents, storage,
                mock(PasswordEncoder.class), "clave", "jdbc:postgresql://localhost:5432/axon").run(null);

        ArgumentCaptor<Professional> pros = ArgumentCaptor.forClass(Professional.class);
        verify(professionals, times(2)).save(pros.capture());
        assertEquals(List.of("MED-4471", "MED-2210"), pros.getAllValues().stream().map(Professional::getIdoneidad).toList());

        ArgumentCaptor<Record> registros = ArgumentCaptor.forClass(Record.class);
        verify(records, times(7)).save(registros.capture());
        assertEquals(EnumSet.allOf(Category.class),
                registros.getAllValues().stream().map(Record::getCategory).collect(Collectors.toCollection(
                        () -> EnumSet.noneOf(Category.class))));

        ArgumentCaptor<Document> docs = ArgumentCaptor.forClass(Document.class);
        verify(documents, times(5)).save(docs.capture());
        assertEquals(3, docs.getAllValues().stream().filter(d -> d.getMimeType().equals("image/jpeg")).count());
        assertEquals(2, docs.getAllValues().stream().filter(Document::isPdf).count());
        verify(storage, times(5)).save(any(), any());
    }

    @Test
    void enUnaBaseYaSembradaReponeSoloLosArchivosQueFaltan() {
        PatientRepository patients = mock(PatientRepository.class);
        RecordRepository records = mock(RecordRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentStorage storage = mock(DocumentStorage.class);
        Document sinArchivo = new Document(1L, "hemograma.pdf", "application/pdf", UUID.randomUUID(), 0);
        Document conArchivo = new Document(2L, "receta-losartan.pdf", "application/pdf", UUID.randomUUID(), 10);
        Document sinEjemplo = new Document(3L, "subido-por-ana.pdf", "application/pdf", UUID.randomUUID(), 0);

        when(patients.count()).thenReturn(1L);
        when(patients.findByCedula("8-912-2043")).thenReturn(Optional.of(new Patient("8-912-2043", "Ana", "x")));
        when(records.findByPatientIdOrderByDocumentDateDescIdDesc(any())).thenReturn(List.of());
        when(documents.findByRecordIdIn(any())).thenReturn(List.of(sinArchivo, conArchivo, sinEjemplo));
        when(storage.read(any())).thenReturn(Optional.empty());
        when(storage.read(conArchivo.getStoragePath())).thenReturn(Optional.of(new byte[10]));

        new DevDataSeeder(patients, mock(ProfessionalRepository.class), records, documents, storage,
                mock(PasswordEncoder.class), "clave", "jdbc:postgresql://localhost:5432/axon").run(null);

        byte[] hemograma = DevDataSeeder.sample("hemograma.pdf");
        verify(storage).save(sinArchivo.getStoragePath(), hemograma);
        verify(storage, times(1)).save(any(), any());
        verify(patients, never()).save(any());
    }
}
