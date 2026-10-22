package pa.axon.dev;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * Datos sintéticos de docs/ESPEC.md §8, solo en el perfil dev y solo contra una base local. Es la
 * "migración de datos" de PB-13 (DEC-25): Flyway correría en cualquier base y no copia archivos.
 * Todo es inventado (RS-15). Cada documento copia al almacén su archivo de ejemplo de
 * {@code src/main/resources/semilla/}, que genera {@code scripts/GenerarSemilla.java}. Si la base ya
 * estaba sembrada, solo repone los archivos de Ana que falten en el almacén.
 */
@Component
@ConditionalOnProperty(name = "axon.seed.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String JPG = "image/jpeg";
    private static final String PDF = "application/pdf";
    private static final String ANA = "8-912-2043";
    private static final Set<String> SAMPLES = Set.of("carne-perinatal.jpg", "receta-losartan.pdf",
            "nota-alergia.jpg", "hemograma.pdf", "carne-vacunas.jpg");

    private final PatientRepository patients;
    private final ProfessionalRepository professionals;
    private final RecordRepository records;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final PasswordEncoder encoder;
    private final String clave;
    private final String datasourceUrl;

    public DevDataSeeder(PatientRepository patients, ProfessionalRepository professionals,
                         RecordRepository records, DocumentRepository documents, DocumentStorage storage,
                         PasswordEncoder encoder, @Value("${axon.seed.clave}") String clave,
                         @Value("${spring.datasource.url}") String datasourceUrl) {
        this.patients = patients;
        this.professionals = professionals;
        this.records = records;
        this.documents = documents;
        this.storage = storage;
        this.encoder = encoder;
        this.clave = clave;
        this.datasourceUrl = datasourceUrl;
    }

    /** Cuentas con una clave conocida solo se siembran en una base de esta máquina (RS-11, RS-15). */
    static boolean isLocal(String jdbcUrl) {
        return jdbcUrl.matches("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1|\\[::1])([:/].*)?$");
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!isLocal(datasourceUrl)) {
            log.warn("Sembrado omitido: la base no es local. No se crean cuentas con clave conocida.");
            return;
        }
        if (patients.count() > 0 || professionals.count() > 0) {
            repairSamples();
            return;
        }
        String hash = encoder.encode(clave);

        Patient ana = patients.save(new Patient(ANA, "Ana Cedeño", hash));
        professionals.save(new Professional("MED-4471", "Dra. Rivera", "Clínica del Este", hash));
        professionals.save(new Professional("MED-2210", "Dr. Solís", "Laboratorio Santa Ana", hash));

        Long id = ana.getId();
        seed(id, Category.DIAGNOSTICOS, "Control prenatal, semana 24", "Embarazo sin complicaciones",
                LocalDate.of(2026, 8, 14), "Policlínica Villa Esperanza", "carne-perinatal.jpg", JPG);
        seed(id, Category.DIAGNOSTICOS, "Dolor lumbar recurrente", "Desde el embarazo, sin estudio aún",
                LocalDate.of(2026, 5, 6), null, null, null);
        seed(id, Category.MEDICAMENTOS, "Losartán 50 mg", "1 tableta diaria, en la mañana",
                LocalDate.of(2026, 1, 12), "Clínica del Este", "receta-losartan.pdf", PDF);
        seed(id, Category.MEDICAMENTOS, "Penicilina, alergia", "Urticaria en todo el cuerpo",
                LocalDate.of(2024, 3, 20), "Centro de Salud de Chilibre", "nota-alergia.jpg", JPG);
        seed(id, Category.LABORATORIOS, "Hemograma completo", "Todo dentro de rango",
                LocalDate.of(2025, 12, 9), "Laboratorio Santa Ana", "hemograma.pdf", PDF);
        seed(id, Category.VACUNAS, "Influenza, dosis anual", null,
                LocalDate.of(2026, 9, 3), "Policlínica Villa Esperanza", "carne-vacunas.jpg", JPG);
        seed(id, Category.OTROS, "Constancia de atención", null,
                LocalDate.of(2026, 7, 22), "Clínica del Este", null, null);

        log.info("Datos sintéticos cargados: 1 paciente, 2 profesionales, 7 registros.");
    }

    private void seed(Long patientId, Category category, String title, String detail, LocalDate date,
                      String institution, String fileName, String mime) {
        Record record = records.save(new Record(patientId, category, title, detail, date, institution));
        if (fileName == null) {
            return;
        }
        UUID storagePath = UUID.randomUUID();
        byte[] content = sample(fileName);
        if (content.length > 0) {
            storage.save(storagePath, content);
        } else {
            log.warn("Falta semilla/{}: el documento queda sin archivo.", fileName);
        }
        documents.save(new Document(record.getId(), fileName, mime, storagePath, content.length));
    }

    /**
     * Las bases sembradas antes de PB-13 tienen los documentos de Ana sin archivo (DEC-15). Se reponen
     * solo los que faltan en el almacén y tienen archivo de ejemplo; lo que ya está no se toca.
     */
    private void repairSamples() {
        Patient ana = patients.findByCedula(ANA).orElse(null);
        if (ana == null) {
            return;
        }
        List<Long> recordIds = records.findByPatientIdOrderByDocumentDateDescIdDesc(ana.getId()).stream()
                .map(Record::getId).toList();
        int repaired = 0;
        for (Document document : documents.findByRecordIdIn(recordIds)) {
            if (storage.read(document.getStoragePath()).isPresent()) {
                continue;
            }
            byte[] content = sample(document.getOriginalName());
            if (content.length > 0) {
                storage.save(document.getStoragePath(), content);
                document.updateSizeBytes(content.length);
                repaired++;
            }
        }
        if (repaired > 0) {
            log.info("Archivos de ejemplo repuestos en el almacén: {}.", repaired);
        }
    }

    /**
     * Solo los archivos de semilla/: el nombre puede venir de la base (nombre_original), y
     * ClassPathResource resuelve los ".." en vez de rechazarlos.
     */
    static byte[] sample(String fileName) {
        if (!SAMPLES.contains(fileName)) {
            return new byte[0];
        }
        ClassPathResource resource = new ClassPathResource("semilla/" + fileName);
        if (!resource.exists()) {
            return new byte[0];
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
