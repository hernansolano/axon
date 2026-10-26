package pa.axon.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * registros nuevos de la paciente (HU-03), los corrige o los elimina (HU-05), y arma su ficha de emergencia
 * con los que ella marca (HU-06).
 */
@Service
public class CartillaService {

    private static final DateTimeFormatter MES_ANIO = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-PA"));

    private final RecordRepository records;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final AuditRepository audit;
    private final EmergencyItemRepository emergencia;
    private final Clock clock;

    public CartillaService(RecordRepository records, DocumentRepository documents, DocumentStorage storage,
                           AuditRepository audit, EmergencyItemRepository emergencia, Clock clock) {
        this.records = records;
        this.documents = documents;
        this.storage = storage;
        this.audit = audit;
        this.emergencia = emergencia;
        this.clock = clock;
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
            guardarDocumento(record.getId(), documento);
        }
        return record.getId();
    }

    /** Hoy sale del mismo reloj que todo lo demás (DEC-28). */
    public LocalDate hoy() {
        return LocalDate.now(clock);
    }

    /** DEC-39: la cartilla guarda lo que ya pasó. */
    public boolean esFutura(LocalDate fecha) {
        return fecha != null && fecha.isAfter(hoy());
    }

    /** Lo que se puede corregir de un registro (HU-05, criterio 1). La institución puede venir vacía. */
    public record Correccion(Category categoria, LocalDate fecha, String institucion) {
    }

    /** Los valores actuales, para llenar el formulario de corregir. Ajeno o inexistente: vacío. */
    @Transactional(readOnly = true)
    public Optional<Correccion> correccion(Long patientId, Long recordId) {
        return records.findByIdAndPatientId(recordId, patientId)
                .map(r -> new Correccion(r.getCategory(), r.getDocumentDate(), r.getOriginInstitution()));
    }

    /**
     * HU-05: corrige categoría, fecha y origen de un registro de la paciente. Si el registro no tenía
     * documento, puede llegar uno (DEC-40, «Fotografiar el papel»), y la procedencia cambia sola. Si ya
     * tenía, el que llegue se ignora: el documento original no se reemplaza. Falso si el registro es
     * ajeno o no existe; el controlador responde 404 en los dos casos.
     */
    @Transactional
    public boolean corregir(Long patientId, Long recordId, Correccion correccion, DocumentUpload documento) {
        Optional<Record> found = records.findByIdAndPatientId(recordId, patientId);
        if (found.isEmpty()) {
            return false;
        }
        Record record = found.get();
        String institucion = correccion.institucion() == null || correccion.institucion().isBlank()
                ? null : correccion.institucion();
        record.corregir(correccion.categoria(), correccion.fecha(), institucion, clock.instant());
        if (documento != null && documents.findByRecordId(recordId).isEmpty()) {
            guardarDocumento(recordId, documento);
        }
        return true;
    }

    /**
     * HU-05: elimina un registro de la paciente. Sale de la cartilla y de todo permiso vigente, porque
     * la consulta del profesional lee la base en cada carga. Se anota REGISTRO_ELIMINADO y no se toca
     * ningún evento anterior (RS-07, CP-07). El archivo se borra solo después del commit: si algo falla,
     * el registro y su documento siguen como estaban. Falso si es ajeno o no existe.
     */
    @Transactional
    public boolean eliminar(Long patientId, Long recordId) {
        Optional<Record> found = records.findByIdAndPatientId(recordId, patientId);
        if (found.isEmpty()) {
            return false;
        }
        Record record = found.get();
        Optional<UUID> archivo = documents.findByRecordId(recordId).map(Document::getStoragePath);
        records.delete(record);
        audit.save(new AuditEntry(patientId, AuditEntry.Type.REGISTRO_ELIMINADO, null, null,
                List.of(record.getCategory()),
                "Eliminaste «" + record.getTitle() + "», de " + record.getCategory().getLabel(), clock.instant()));
        archivo.ifPresent(storagePath -> {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        storage.delete(storagePath);
                    }
                });
            } else {
                storage.delete(storagePath);
            }
        });
        return true;
    }

    /** Una línea de la ficha: la etiqueta es la categoría, el dato es el título (UI.md 12, DEC-42). */
    public record LineaFicha(Long registroId, String etiqueta, String dato, boolean marcada) {
    }

    /** La ficha como la configura la paciente: todos sus registros, cuáles marcó, y esos unidos para el resumen. */
    public record FichaConfig(List<LineaFicha> lineas, String marcadas) {
        public boolean vacia() {
            return lineas.stream().noneMatch(LineaFicha::marcada);
        }
    }

    /** Lo único que verá urgencias: etiqueta, dato y fecha de lo marcado; ni ids, ni documentos, ni el resto. */
    public record LineaUrgencias(String etiqueta, String dato, String fecha) {
    }

    @Transactional(readOnly = true)
    public FichaConfig ficha(Long patientId) {
        Set<Long> marcados = emergencia.findByPatientId(patientId).stream()
                .map(EmergencyItem::getRecordId).collect(Collectors.toSet());
        List<LineaFicha> lineas = records.findByPatientIdOrderByDocumentDateDescIdDesc(patientId).stream()
                .sorted(Comparator.comparing(Record::getCategory))
                .map(r -> new LineaFicha(r.getId(), r.getCategory().getLabel().toLowerCase(Locale.ROOT), r.getTitle(),
                        marcados.contains(r.getId())))
                .toList();
        return new FichaConfig(lineas, unir(lineas.stream().filter(LineaFicha::marcada).map(LineaFicha::dato).toList()));
    }

    /**
     * HU-06: la ficha queda con exactamente lo marcado. Solo cuentan los registros de esta paciente: un
     * id ajeno o inexistente se descarta sin decir nada (RS-02). Se borra lo desmarcado y se agrega lo
     * nuevo, sin tocar lo que sigue igual.
     */
    @Transactional
    public void guardarFicha(Long patientId, Collection<Long> recordIds) {
        Set<Long> propios = recordIds.isEmpty() ? Set.of()
                : records.findByPatientIdAndIdIn(patientId, recordIds).stream().map(Record::getId).collect(Collectors.toSet());
        List<EmergencyItem> actuales = emergencia.findByPatientId(patientId);
        Set<Long> yaEstan = new HashSet<>();
        for (EmergencyItem item : actuales) {
            if (propios.contains(item.getRecordId())) {
                yaEstan.add(item.getRecordId());
            } else {
                emergencia.delete(item);
            }
        }
        propios.stream().filter(id -> !yaEstan.contains(id)).sorted()
                .forEach(id -> emergencia.save(new EmergencyItem(patientId, id)));
    }

    /**
     * Lo que se mostrará en urgencias (HU-12 la abrirá en el Sprint 7). Una ficha sin nada marcado
     * devuelve una lista vacía sin leer un solo registro (CP-13, RS-08).
     */
    @Transactional(readOnly = true)
    public List<LineaUrgencias> fichaParaUrgencias(Long patientId) {
        List<Long> ids = emergencia.findByPatientId(patientId).stream().map(EmergencyItem::getRecordId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return records.findByPatientIdAndIdIn(patientId, ids).stream()
                .sorted(Comparator.comparing(Record::getCategory))
                .map(r -> {
                    String fecha = r.getDocumentDate().format(MES_ANIO);
                    return new LineaUrgencias(r.getCategory().getLabel().toLowerCase(Locale.ROOT), r.getTitle(),
                            Character.toUpperCase(fecha.charAt(0)) + fecha.substring(1));
                })
                .toList();
    }

    /** Lo que va en negrita en el resumen de UI.md 12, separado por comas como en v4. Vacío si no hay nada. */
    static String unir(List<String> marcados) {
        return String.join(", ", marcados);
    }

    /**
     * El archivo se escribe al final, con su UUID como nombre: si falla, o si la transacción se deshace,
     * se borra y no queda un registro que diga tener un documento que no está.
     */
    private void guardarDocumento(Long recordId, DocumentUpload documento) {
        UUID storagePath = UUID.randomUUID();
        documents.save(new Document(recordId, documento.originalName(), documento.mimeType(),
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
