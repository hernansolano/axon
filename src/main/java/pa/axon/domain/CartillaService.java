package pa.axon.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Arma la cartilla del paciente: agrupada por categoría y con la más reciente primero (RF-04), el
 * detalle de cada registro y sus documentos. El filtro por paciente ocurre en la consulta, antes de
 * construir el modelo de la vista. Lo ajeno y lo inexistente devuelven lo mismo, vacío, para que
 * nadie pueda confirmar que un registro o un documento existe (RS-02, RS-13). También guarda los
 * registros nuevos de la paciente (HU-03).
 */
@Service
public class CartillaService {

    private static final DateTimeFormatter MES_ANIO = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-PA"));

    private final RecordRepository records;
    private final DocumentRepository documents;
    private final DocumentStorage storage;

    public CartillaService(RecordRepository records, DocumentRepository documents, DocumentStorage storage) {
        this.records = records;
        this.documents = documents;
        this.storage = storage;
    }

    /** {@code documentoId} solo lo lleva la vista del profesional, que abre el documento por su propia ruta. */
    public record RegistroVista(Long id, String titulo, String detalle, String meta,
                                boolean conDocumento, String tipoDocumento, UUID documentoId) {

        public RegistroVista(Long id, String titulo, String detalle, String meta, boolean conDocumento, String tipoDocumento) {
            this(id, titulo, detalle, meta, conDocumento, tipoDocumento, null);
        }

        public boolean esPdf() {
            return "PDF".equals(tipoDocumento);
        }
    }

    public record GrupoVista(Category categoria, String conteo, List<RegistroVista> registros) {
    }

    public record CartillaVista(int total, String totalTexto, List<GrupoVista> grupos) {
        public boolean vacia() {
            return total == 0;
        }
    }

    /** {@code documentoId} es el UUID de ruta_almacen; nulo si el registro no tiene documento. */
    public record DetalleVista(Long id, Category categoria, String titulo, String detalle, String fecha,
                               String institucion, UUID documentoId, String tipoDocumento) {
        public boolean conDocumento() {
            return documentoId != null;
        }

        public boolean esPdf() {
            return "PDF".equals(tipoDocumento);
        }

        public String procedencia() {
            return conDocumento() ? "Con documento · " + tipoDocumento : "Lo dijo la paciente, sin documento";
        }
    }

    public record ArchivoVista(String tipoMime, byte[] contenido) {
    }

    /** Lo que la paciente escribe al agregar un registro. La institución puede venir vacía. */
    public record NuevoRegistro(Category categoria, String titulo, LocalDate fecha, String institucion) {
    }

    @Transactional(readOnly = true)
    public CartillaVista deLaPaciente(Long patientId) {
        return agrupar(records.findByPatientIdOrderByDocumentDateDescIdDesc(patientId), false);
    }

    /**
     * Lo que ve el profesional (HU-08): solo las categorías del alcance. El filtro va en la consulta a
     * la base, así lo demás ni se lee ni puede llegar a la vista (RS-04, RS-05).
     */
    @Transactional(readOnly = true)
    public CartillaVista autorizada(Long patientId, Collection<Category> alcance) {
        if (alcance.isEmpty()) {
            return new CartillaVista(0, count(0, "registro"), List.of());
        }
        return agrupar(records.findByPatientIdAndCategoryInOrderByDocumentDateDescIdDesc(patientId, alcance), true);
    }

    private CartillaVista agrupar(List<Record> mine, boolean conIdDeDocumento) {
        Map<Long, Document> byRecord = documents.findByRecordIdIn(mine.stream().map(Record::getId).toList()).stream()
                .collect(Collectors.toMap(Document::getRecordId, Function.identity()));

        Map<Category, List<RegistroVista>> grouped = new EnumMap<>(Category.class);
        for (Record r : mine) {
            Document doc = byRecord.get(r.getId());
            grouped.computeIfAbsent(r.getCategory(), k -> new ArrayList<>()).add(new RegistroVista(
                    r.getId(), r.getTitle(), r.getDetail(),
                    r.getDocumentDate().format(MES_ANIO) + " · " + origin(r),
                    doc != null, type(doc), conIdDeDocumento && doc != null ? doc.getStoragePath() : null));
        }
        List<GrupoVista> groups = grouped.entrySet().stream()
                .map(e -> new GrupoVista(e.getKey(), count(e.getValue().size(), "registro"), e.getValue()))
                .toList();
        return new CartillaVista(mine.size(), count(mine.size(), "registro"), groups);
    }

    /** Vacío si el registro no existe o no es de esta paciente: el controlador responde 404 en los dos casos. */
    @Transactional(readOnly = true)
    public Optional<DetalleVista> detalle(Long patientId, Long recordId) {
        return records.findByIdAndPatientId(recordId, patientId).map(r -> {
            Document doc = documents.findByRecordId(r.getId()).orElse(null);
            String fecha = r.getDocumentDate().format(MES_ANIO);
            return new DetalleVista(r.getId(), r.getCategory(), r.getTitle(), r.getDetail(),
                    Character.toUpperCase(fecha.charAt(0)) + fecha.substring(1), origin(r),
                    doc == null ? null : doc.getStoragePath(), type(doc));
        });
    }

    /**
     * El archivo solo sale si el documento es de un registro de esta paciente y sigue en el disco.
     * Inexistente, ajeno o borrado del disco: vacío, y el controlador responde 404 igual.
     * El profesional entra por {@link #documentoAutorizado}, que además exige el alcance (H4).
     */
    @Transactional(readOnly = true)
    public Optional<ArchivoVista> documento(Long patientId, UUID documentId) {
        return documents.findOwned(documentId, patientId)
                .flatMap(doc -> storage.read(doc.getStoragePath())
                        .map(bytes -> new ArchivoVista(doc.getMimeType(), bytes)));
    }

    /**
     * El documento solo sale si su registro es de la paciente del permiso y está en una categoría del
     * alcance. La vigencia y quién canjeó los revisa antes {@link ConsultaService}.
     */
    @Transactional(readOnly = true)
    public Optional<ArchivoVista> documentoAutorizado(Long patientId, Collection<Category> alcance, UUID documentId) {
        if (alcance.isEmpty()) {
            return Optional.empty();
        }
        return documents.findInScope(documentId, patientId, alcance)
                .flatMap(doc -> storage.read(doc.getStoragePath())
                        .map(bytes -> new ArchivoVista(doc.getMimeType(), bytes)));
    }

    /**
     * Guarda un registro en la cartilla de la paciente de la sesión (HU-03). Con documento queda
     * CON_DOCUMENTO porque existe su fila en documento; sin él, AUTODECLARADO (ESPEC §2). El archivo se
     * escribe al final, con su UUID como nombre: si falla, o si la transacción se deshace, se borra y no
     * queda un registro que diga tener un documento que no está. Si no se sabe cómo terminó el commit,
     * el archivo se queda: a lo sumo sobra un archivo que nadie nombra y que /documento/{id} no sirve.
     */
    @Transactional
    public Long agregar(Long patientId, NuevoRegistro nuevo, DocumentUpload documento) {
        String institucion = nuevo.institucion() == null || nuevo.institucion().isBlank() ? null : nuevo.institucion();
        Record record = records.save(new Record(patientId, nuevo.categoria(), nuevo.titulo(), null, nuevo.fecha(), institucion));
        if (documento != null) {
            UUID storagePath = UUID.randomUUID();
            documents.save(new Document(record.getId(), documento.originalName(), documento.mimeType(),
                    storagePath, documento.content().length));
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            storage.delete(storagePath);
                        }
                    }
                });
            }
            storage.save(storagePath, documento.content());
        }
        return record.getId();
    }

    private static String origin(Record r) {
        return r.getOriginInstitution() == null || r.getOriginInstitution().isBlank()
                ? "Sin institución anotada" : r.getOriginInstitution();
    }

    /** La procedencia se deriva de que exista el documento; no se guarda (ESPEC §2). */
    private static String type(Document doc) {
        return doc == null ? null : (doc.isPdf() ? "PDF" : "imagen");
    }

    private static String count(int n, String noun) {
        return n == 1 ? "1 " + noun : n + " " + noun + "s";
    }
}
