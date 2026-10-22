package pa.axon.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permisos de la paciente (HU-07, HU-09): los crea con el alcance y la vigencia que ella elige, los
 * lista con su estado calculado con la hora de {@link Clock} (DEC-28) y los revoca. Crear y revocar
 * dejan su fila en la bitácora en la misma transacción (RF-11). Todo se busca por la paciente de la
 * sesión: lo ajeno y lo inexistente dan vacío.
 */
@Service
public class PermissionService {

    /** Los propósitos del diseño v4 (DEC-29). Cualquier otro texto que llegue se rechaza. */
    public static final List<String> PROPOSITOS = List.of(
            "Consulta general", "Entrega de resultados", "Segunda opinión", "Control prenatal");

    private static final int INTENTOS_CODIGO = 20;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("H:mm");
    private static final String[] MESES = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"};

    private final PermissionRepository permissions;
    private final AuditRepository audit;
    private final RecordRepository records;
    private final ProfessionalRepository professionals;
    private final CodigoGenerator codes;
    private final Clock clock;

    public PermissionService(PermissionRepository permissions, AuditRepository audit, RecordRepository records,
                             ProfessionalRepository professionals, CodigoGenerator codes, Clock clock) {
        this.permissions = permissions;
        this.audit = audit;
        this.records = records;
        this.professionals = professionals;
        this.codes = codes;
        this.clock = clock;
    }

    /** Una casilla de la pantalla de compartir, con cuántos registros hay debajo. */
    public record Opcion(Category categoria, String conteo) {
    }

    /** Lo que la paciente eligió. El controlador ya comprobó que el propósito es uno de la lista. */
    public record NuevoPermiso(Set<Category> alcance, ShareDuration vigencia, String proposito) {
    }

    /** El resumen de la pantalla de compartir: lo que queda fuera se dice, no solo lo que entra. */
    public record Resumen(String quedaFuera, String nota) {
    }

    public record PermisoVista(Long id, String codigo, Permission.Status estado, String quien, List<Category> alcance,
                               String alcanceTexto, String quedaFuera, String proposito, String generado,
                               long restanteSeg, long totalSeg) {

        public boolean activo() {
            return estado == Permission.Status.ACTIVO;
        }

        public boolean vencido() {
            return estado == Permission.Status.VENCIDO;
        }

        public boolean revocado() {
            return estado == Permission.Status.REVOCADO;
        }

        /** activo, vencido o revocado: para los data-testid y las clases. */
        public String estadoSlug() {
            return estado.name().toLowerCase(Locale.ROOT);
        }

        public String estadoTexto() {
            return "Permiso " + estadoSlug();
        }

        /** «AFT · UN4», debajo del código grande. */
        public String deletreado() {
            return codigo.replace("-", " · ");
        }

        /** Los caracteres del código uno por uno, guion incluido, para dibujar cada casilla. */
        public List<String> caracteres() {
            return codigo.chars().mapToObj(c -> String.valueOf((char) c)).toList();
        }

        /** «Alcance: medicamentos y alergias.» */
        public String alcanceFrase() {
            return "Alcance: " + Category.join(alcance) + ".";
        }

        public String meta() {
            return proposito + " · generado " + generado;
        }

        public String restante() {
            return PermissionService.restante(restanteSeg);
        }

        /** Lo que queda de la barra, de 100 % al crearlo a 0 % al vencer. */
        public String porcentaje() {
            double v = totalSeg <= 0 ? 0 : Math.max(0, Math.min(1, (double) restanteSeg / totalSeg));
            return String.format(Locale.ROOT, "%.2f%%", v * 100);
        }
    }

    @Transactional(readOnly = true)
    public List<Opcion> opciones(Long patientId) {
        Map<Category, Long> counts = new EnumMap<>(Category.class);
        records.countByCategory(patientId).forEach(c -> counts.put(c.getCategory(), c.getTotal()));
        return EnumSet.allOf(Category.class).stream()
                .map(c -> new Opcion(c, conteo(counts.getOrDefault(c, 0L))))
                .toList();
    }

    public static Resumen resumen(Collection<Category> marcadas) {
        Set<Category> fuera = EnumSet.allOf(Category.class);
        fuera.removeAll(marcadas);
        if (fuera.isEmpty()) {
            return new Resumen("Nada. Le muestras la cartilla completa.",
                    "Puedes desmarcar lo que no haga falta para esta consulta.");
        }
        return new Resumen(capitalizar(Category.join(fuera)), "El médico no lo ve ni sabe que existe.");
    }

    /**
     * Crea el permiso con un código que no exista y deja PERMISO_CREADO en la bitácora (HU-07, criterio 7).
     * Vence a la hora de crearlo más la vigencia; nada lo extiende.
     */
    @Transactional
    public Long crear(Long patientId, NuevoPermiso nuevo) {
        if (nuevo.alcance() == null || nuevo.alcance().isEmpty()) {
            throw new IllegalArgumentException("Un permiso necesita al menos una categoría");
        }
        if (nuevo.vigencia() == null || nuevo.proposito() == null || !PROPOSITOS.contains(nuevo.proposito())) {
            throw new IllegalArgumentException("Vigencia o propósito fuera de la lista");
        }
        Instant now = clock.instant();
        Permission permission = permissions.save(new Permission(patientId, codigoLibre(), nuevo.alcance(),
                nuevo.proposito(), now, now.plus(nuevo.vigencia().toDuration())));
        audit.save(new AuditEntry(patientId, AuditEntry.Type.PERMISO_CREADO, null, permission.getId(),
                permission.getScope(), null, now));
        return permission.getId();
    }

    @Transactional(readOnly = true)
    public List<PermisoVista> deLaPaciente(Long patientId) {
        Instant now = clock.instant();
        return permissions.findByPatientIdOrderByCreatedAtDescIdDesc(patientId).stream()
                .map(p -> vista(p, now))
                .toList();
    }

    /** Vacío si no existe o no es de esta paciente: el controlador responde 404 en los dos casos. */
    @Transactional(readOnly = true)
    public Optional<PermisoVista> uno(Long patientId, Long permissionId) {
        Instant now = clock.instant();
        return permissions.findByIdAndPatientId(permissionId, patientId).map(p -> vista(p, now));
    }

    /**
     * Revoca con efecto inmediato (HU-09): el código deja de servir en cuanto termina la transacción y
     * queda PERMISO_REVOCADO en la bitácora, que no se toca. Vacío si el permiso no es de la paciente.
     * Si ya había vencido o estaba revocado no cambia nada y el aviso es nulo.
     */
    @Transactional
    public Optional<String> revocar(Long patientId, Long permissionId) {
        Optional<Permission> found = permissions.lockOwned(permissionId, patientId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Permission p = found.get();
        Instant now = clock.instant();
        if (permissions.revoke(p.getId(), patientId, now) == 0) {
            return Optional.of("");
        }
        audit.save(new AuditEntry(patientId, AuditEntry.Type.PERMISO_REVOCADO, p.getProfessionalId(), p.getId(),
                p.getScope(), null, now));
        String quien = quien(p);
        String corte = p.getProfessionalId() == null
                ? "El código " + p.getCode() + " ya no sirve."
                : quien + " ya no puede ver nada.";
        return Optional.of(corte + " Lo que vio antes sigue anotado en tu actividad: revocar no borra el historial.");
    }

    private String codigoLibre() {
        for (int i = 0; i < INTENTOS_CODIGO; i++) {
            String code = codes.next();
            if (!permissions.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("No se encontró un código libre");
    }

    private PermisoVista vista(Permission p, Instant now) {
        Set<Category> fuera = EnumSet.allOf(Category.class);
        fuera.removeAll(p.getScope());
        String quedaFuera = fuera.isEmpty() ? "Cartilla completa." : "Queda fuera: " + Category.join(fuera) + ".";
        long restante = Math.max(0, segundosHacia(now, p.getExpiresAt()));
        long total = Duration.between(p.getCreatedAt(), p.getExpiresAt()).toSeconds();
        Permission.Status estado = p.status(now);
        return new PermisoVista(p.getId(), p.getCode(), estado, quien(p), p.getScope(),
                capitalizar(Category.join(p.getScope())), quedaFuera, p.getPurpose(), generado(p.getCreatedAt(), now),
                estado == Permission.Status.ACTIVO ? restante : 0, total);
    }

    private String quien(Permission p) {
        if (p.getProfessionalId() == null) {
            return "Sin canjear";
        }
        return professionals.findById(p.getProfessionalId())
                .map(pro -> pro.getNombre() + " · " + pro.getInstitucion())
                .orElse("Sin canjear");
    }

    private String generado(Instant creado, Instant now) {
        return cuando(creado, now, clock.getZone());
    }

    /** «ahora mismo», «hoy 8:55», «ayer 16:10» o «12 oct, 9:30», en la hora de Panamá. También lo usa Actividad. */
    static String cuando(Instant creado, Instant now, ZoneId zona) {
        if (Duration.between(creado, now).toSeconds() < 60) {
            return "ahora mismo";
        }
        ZonedDateTime c = creado.atZone(zona);
        LocalDate hoy = now.atZone(zona).toLocalDate();
        if (c.toLocalDate().equals(hoy)) {
            return "hoy " + c.format(HORA);
        }
        if (c.toLocalDate().equals(hoy.minusDays(1))) {
            return "ayer " + c.format(HORA);
        }
        return c.getDayOfMonth() + " " + MESES[c.getMonthValue() - 1] + ", " + c.format(HORA);
    }

    /** Redondea hacia arriba: mientras quede un instante, el contador no dice cero. */
    private static long segundosHacia(Instant now, Instant hasta) {
        long ms = Duration.between(now, hasta).toMillis();
        return ms <= 0 ? 0 : (ms + 999) / 1000;
    }

    /** Como en v4: «1 h 59 min» desde una hora, «37:18» por debajo, y «vencido» en cero. */
    static String restante(long segundos) {
        if (segundos <= 0) {
            return "vencido";
        }
        if (segundos >= 3600) {
            return segundos / 3600 + " h " + String.format(Locale.ROOT, "%02d", segundos % 3600 / 60) + " min";
        }
        return String.format(Locale.ROOT, "%02d:%02d", segundos / 60, segundos % 60);
    }

    private static String conteo(long n) {
        if (n == 0) {
            return "Nada guardado";
        }
        return n == 1 ? "1 registro" : n + " registros";
    }

    private static String capitalizar(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
