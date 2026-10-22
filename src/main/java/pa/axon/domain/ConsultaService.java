package pa.axon.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pa.axon.domain.CartillaService.ArchivoVista;
import pa.axon.domain.CartillaService.GrupoVista;
import pa.axon.security.AccountType;
import pa.axon.security.LoginAttemptService;

/**
 * El lado del profesional (HU-08): canjear un código y consultar solo lo que autoriza, mientras esté
 * vigente. Tener cuenta no da acceso a nada: todo se busca por el permiso que canjeó este profesional,
 * y cada lectura vuelve a validar estado, vigencia y alcance con la hora de {@link Clock} (RS-03,
 * RS-04, DEC-28). Canjes, consultas, denegaciones y solicitudes quedan en la bitácora de la paciente
 * en la misma transacción (RF-11).
 */
@Service
public class ConsultaService {

    /** DEC-32: con 10 canjes fallidos en 15 minutos, esa cuenta no canjea hasta que salgan de la ventana. */
    public static final int TOPE_FALLOS = 10;
    public static final Duration VENTANA = Duration.ofMinutes(15);
    static final String MOTIVO = "CANJE_";
    private static final Set<String> SERVIBLES = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");

    /** Cómo terminó un canje, con el texto que ve el profesional (UI.md 16 en la versión v4, DEC-34). */
    public enum Resultado {
        CANJEADO(null, null, null),
        VACIO("Falta el código", "Escriba el código",
                "Son seis caracteres, con o sin guion."),
        INEXISTENTE("Código inválido", "Ese código no existe",
                "Revise los seis caracteres con la paciente. No hay O ni I ni números 0, 1, 5 y 8 para evitar confusiones."),
        VENCIDO("Código vencido", "Ese código ya venció",
                "El permiso tenía una duración fijada por la paciente y se cumplió. Pídale que genere uno nuevo: toma diez segundos."),
        REVOCADO("Código revocado", "La paciente revocó este código",
                "Lo cortó antes de que venciera. No se puede reactivar; solo ella puede generar otro."),
        AJENO("Código ya usado", "Ese código ya lo canjeó otro profesional",
                "Cada código sirve para una sola cuenta. Pídale a la paciente que genere uno para usted."),
        BLOQUEADO("Demasiados intentos", "Espere antes de volver a intentar",
                "Hubo 10 códigos que no sirvieron en 15 minutos. Podrá canjear de nuevo cuando pasen.");

        private final String etiqueta;
        private final String titulo;
        private final String texto;

        Resultado(String etiqueta, String titulo, String texto) {
            this.etiqueta = etiqueta;
            this.titulo = titulo;
            this.texto = texto;
        }

        public String getEtiqueta() {
            return etiqueta;
        }

        public String getTitulo() {
            return titulo;
        }

        public String getTexto() {
            return texto;
        }

        /** Para los data-testid: alerta-codigo-invalido, alerta-codigo-vencido… */
        public String getSlug() {
            return this == INEXISTENTE ? "invalido" : name().toLowerCase(Locale.ROOT);
        }

        static Resultado de(Permission.Status estado) {
            return estado == Permission.Status.REVOCADO ? REVOCADO : VENCIDO;
        }
    }

    public record Canje(Resultado resultado, Long permisoId) {
    }

    public record ConsultaVista(Long permisoId, String paciente, String iniciales, String proposito,
                                long restanteSeg, long totalSeg, List<Category> alcance, List<Category> fuera,
                                List<GrupoVista> grupos) {

        public String alcanceTexto() {
            String s = Category.join(alcance);
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        public String restante() {
            return PermissionService.restante(restanteSeg);
        }

        public String porcentaje() {
            double v = totalSeg <= 0 ? 0 : Math.max(0, Math.min(1, (double) restanteSeg / totalSeg));
            return String.format(Locale.ROOT, "%.2f%%", v * 100);
        }
    }

    /** Si el permiso ya no sirve, {@code vista} es nula y {@code corte} dice por qué. */
    public record Acceso(ConsultaVista vista, Resultado corte) {
        public boolean vigente() {
            return vista != null;
        }
    }

    /** Lo único que devuelve la ruta de estado (DEC-30): nada de la cartilla. */
    public record Estado(boolean vigente, long restanteSeg, Resultado corte) {
    }

    /** Una categoría pedida fuera del alcance (UI.md 18). */
    public record Pedido(Category categoria, String autorizado) {
    }

    private final PermissionRepository permissions;
    private final AuditRepository audit;
    private final PatientRepository patients;
    private final CartillaService cartilla;
    private final LoginAttemptService attempts;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public ConsultaService(PermissionRepository permissions, AuditRepository audit, PatientRepository patients,
                           CartillaService cartilla, LoginAttemptService attempts, Clock clock, JdbcTemplate jdbc) {
        this.permissions = permissions;
        this.audit = audit;
        this.patients = patients;
        this.cartilla = cartilla;
        this.attempts = attempts;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    /**
     * HU-08, criterios 1 a 3 y 5. El código no distingue mayúsculas ni exige el guion. Un código
     * inexistente, vencido, revocado o de otro profesional cuenta para el tope (DEC-32); los tres
     * últimos además se anotan en la bitácora de la paciente (DEC-31). El mismo profesional puede
     * volver a canjear su código para reabrir la consulta.
     */
    @Transactional
    public Canje canjear(Long professionalId, String idoneidad, String tecleado, String origen) {
        // Un canje a la vez por profesional, hasta el final de la transacción: una ráfaga de pedidos en
        // paralelo no puede contar los fallos antes de que los otros los guarden (guardián, H4).
        jdbc.queryForList("select pg_advisory_xact_lock(?)", professionalId);
        Instant now = clock.instant();
        if (attempts.recentFailures(AccountType.PROFESIONAL, idoneidad, MOTIVO, now.minus(VENTANA)) >= TOPE_FALLOS) {
            return new Canje(Resultado.BLOQUEADO, null);
        }
        String limpio = tecleado == null ? "" : tecleado.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (limpio.isEmpty()) {
            return new Canje(Resultado.VACIO, null);
        }
        Optional<Permission> found = normalizar(limpio).flatMap(permissions::lockByCode);
        if (found.isEmpty()) {
            return fallo(idoneidad, origen, Resultado.INEXISTENTE);
        }
        Permission p = found.get();
        // Primero si es de otro: así no se entera de si un permiso ajeno venció o lo revocaron.
        if (p.getProfessionalId() != null && !p.getProfessionalId().equals(professionalId)) {
            return denegarCanje(p, professionalId, idoneidad, origen, Resultado.AJENO, now);
        }
        Permission.Status estado = p.status(now);
        if (estado != Permission.Status.ACTIVO) {
            return denegarCanje(p, professionalId, idoneidad, origen, Resultado.de(estado), now);
        }
        if (p.redeem(professionalId, now)) {
            audit.save(new AuditEntry(p.getPatientId(), AuditEntry.Type.PERMISO_CANJEADO, professionalId, p.getId(),
                    p.getScope(), null, now));
        }
        return new Canje(Resultado.CANJEADO, p.getId());
    }

    /**
     * La consulta (UI.md 17). Vacío si el permiso no lo canjeó este profesional: el controlador
     * responde 404. Vigente, anota CONSULTA; si ya no sirve, anota la denegación y no arma la cartilla.
     */
    @Transactional
    public Optional<Acceso> consulta(Long professionalId, Long permissionId) {
        Instant now = clock.instant();
        return permissions.findByIdAndProfessionalId(permissionId, professionalId).map(p -> {
            Permission.Status estado = p.status(now);
            if (estado != Permission.Status.ACTIVO) {
                anotar(p, professionalId, AuditEntry.Type.CONSULTA_DENEGADA, p.getScope(),
                        "Abrió la consulta con el permiso " + estado.name().toLowerCase(Locale.ROOT), now);
                return new Acceso(null, Resultado.de(estado));
            }
            anotar(p, professionalId, AuditEntry.Type.CONSULTA, p.getScope(), null, now);
            String nombre = patients.findById(p.getPatientId()).map(Patient::getNombre).orElse("");
            Set<Category> fuera = EnumSet.allOf(Category.class);
            fuera.removeAll(p.getScope());
            return new Acceso(new ConsultaVista(p.getId(), nombre, iniciales(nombre), p.getPurpose(),
                    restante(now, p), Duration.between(p.getCreatedAt(), p.getExpiresAt()).toSeconds(),
                    p.getScope(), List.copyOf(fuera), cartilla.autorizada(p.getPatientId(), p.getScope()).grupos()), null);
        });
    }

    /** DEC-30: lo que pregunta la pantalla cada 5 s. No anota nada ni devuelve datos de la cartilla. */
    @Transactional(readOnly = true)
    public Optional<Estado> estado(Long professionalId, Long permissionId) {
        Instant now = clock.instant();
        return permissions.findByIdAndProfessionalId(permissionId, professionalId).map(p -> {
            Permission.Status estado = p.status(now);
            return estado == Permission.Status.ACTIVO
                    ? new Estado(true, restante(now, p), null)
                    : new Estado(false, 0, Resultado.de(estado));
        });
    }

    /**
     * HU-08, criterios 6 y 7 (UI.md 18). Vacío si el permiso no es suyo, si ya no está vigente o si la
     * categoría sí está en el alcance: el controlador vuelve a la consulta, que dice qué pasó.
     */
    @Transactional
    public Optional<Pedido> denegar(Long professionalId, Long permissionId, Category categoria) {
        return fueraDelAlcance(professionalId, permissionId, categoria, AuditEntry.Type.CONSULTA_DENEGADA,
                "Pidió " + categoria.getShortLabel() + ", fuera del alcance");
    }

    /** PB-19 (DEC-33): la solicitud de ampliación queda en la bitácora; el permiso no cambia. */
    @Transactional
    public Optional<Pedido> solicitar(Long professionalId, Long permissionId, Category categoria) {
        // Una vez por permiso y categoría: repetirla no llena la actividad de la paciente. El bloqueo del
        // profesional evita que dos pedidos a la vez lean «no está» y anoten los dos (guardián, H4).
        jdbc.queryForList("select pg_advisory_xact_lock(?)", professionalId);
        if (audit.alreadyRequested(permissionId, professionalId, categoria.name())) {
            return permissions.findByIdAndProfessionalId(permissionId, professionalId)
                    .filter(p -> p.status(clock.instant()) == Permission.Status.ACTIVO)
                    .filter(p -> !p.getScope().contains(categoria))
                    .map(p -> pedido(p, categoria));
        }
        return fueraDelAlcance(professionalId, permissionId, categoria, AuditEntry.Type.SOLICITUD_AMPLIACION,
                "Pidió agregar " + categoria.getShortLabel());
    }

    /**
     * El documento de un registro autorizado (DEC-34): permiso de este profesional, vigente, y registro
     * de la paciente dentro del alcance. Lo demás es vacío, y el controlador responde 404.
     */
    @Transactional
    public Optional<ArchivoVista> documento(Long professionalId, Long permissionId, UUID documentId) {
        Instant now = clock.instant();
        Optional<Permission> found = permissions.findByIdAndProfessionalId(permissionId, professionalId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Permission p = found.get();
        Permission.Status estado = p.status(now);
        // Toda denegación queda anotada (guardián, H4): con el permiso cortado, o un documento fuera del alcance.
        if (estado != Permission.Status.ACTIVO) {
            anotar(p, professionalId, AuditEntry.Type.CONSULTA_DENEGADA, p.getScope(),
                    "Intentó abrir un documento con el permiso " + estado.name().toLowerCase(Locale.ROOT), now);
            return Optional.empty();
        }
        Optional<ArchivoVista> archivo = cartilla.documentoAutorizado(p.getPatientId(), p.getScope(), documentId);
        if (archivo.isEmpty()) {
            anotar(p, professionalId, AuditEntry.Type.CONSULTA_DENEGADA, p.getScope(),
                    "Intentó abrir un documento fuera del permiso", now);
            return Optional.empty();
        }
        // Solo tipos que el navegador no ejecuta; se anota lo que de verdad se sirve, y lo demás como denegado.
        Optional<ArchivoVista> servible = archivo.filter(a -> SERVIBLES.contains(a.tipoMime()));
        anotar(p, professionalId, servible.isPresent() ? AuditEntry.Type.CONSULTA : AuditEntry.Type.CONSULTA_DENEGADA,
                p.getScope(), servible.isPresent() ? "Abrió un documento" : "Intentó abrir un documento de un tipo que no se sirve", now);
        return servible;
    }

    private Optional<Pedido> fueraDelAlcance(Long professionalId, Long permissionId, Category categoria,
                                             AuditEntry.Type tipo, String detalle) {
        Instant now = clock.instant();
        return permissions.findByIdAndProfessionalId(permissionId, professionalId)
                .filter(p -> p.status(now) == Permission.Status.ACTIVO)
                .filter(p -> !p.getScope().contains(categoria))
                .map(p -> {
                    anotar(p, professionalId, tipo, List.of(categoria), detalle, now);
                    return pedido(p, categoria);
                });
    }

    private static Pedido pedido(Permission p, Category categoria) {
        String s = Category.join(p.getScope());
        return new Pedido(categoria, Character.toUpperCase(s.charAt(0)) + s.substring(1));
    }

    private Canje denegarCanje(Permission p, Long professionalId, String idoneidad, String origen,
                               Resultado resultado, Instant now) {
        anotar(p, professionalId, AuditEntry.Type.CONSULTA_DENEGADA, p.getScope(),
                "Intentó canjear el código " + p.getCode() + ": " + resultado.getEtiqueta().toLowerCase(Locale.ROOT), now);
        return fallo(idoneidad, origen, resultado);
    }

    private Canje fallo(String idoneidad, String origen, Resultado resultado) {
        attempts.recordRedeemFailure(AccountType.PROFESIONAL, idoneidad, origen, MOTIVO + resultado.name());
        return new Canje(resultado, null);
    }

    private void anotar(Permission p, Long professionalId, AuditEntry.Type tipo, List<Category> categorias,
                        String detalle, Instant now) {
        audit.save(new AuditEntry(p.getPatientId(), tipo, professionalId, p.getId(), categorias, detalle, now));
    }

    /** «aft un4» o «AFTUN4» → «AFT-UN4». Fuera del alfabeto de ESPEC §4 no puede existir: vacío. */
    static Optional<String> normalizar(String limpio) {
        if (limpio.length() != 6 || !limpio.chars().allMatch(c -> CodigoGenerator.ALFABETO.indexOf(c) >= 0)) {
            return Optional.empty();
        }
        return Optional.of(limpio.substring(0, 3) + "-" + limpio.substring(3));
    }

    private static long restante(Instant now, Permission p) {
        long ms = Duration.between(now, p.getExpiresAt()).toMillis();
        return ms <= 0 ? 0 : (ms + 999) / 1000;
    }

    private static String iniciales(String nombre) {
        return Arrays.stream(nombre.trim().split("\\s+")).filter(s -> !s.isEmpty()).limit(2)
                .map(s -> s.substring(0, 1).toUpperCase(Locale.ROOT)).collect(Collectors.joining());
    }
}
