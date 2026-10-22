package pa.axon.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Actividad de la paciente (HU-10, UI.md 13, diseño v4): sus filas de bitácora, de la más reciente a la
 * más antigua, contadas en palabras. Solo lee; la bitácora no tiene ruta que la cambie (RS-07). La
 * paciente sale de la sesión, así que no hay forma de pedir la actividad de otra.
 */
@Service
public class ActivityService {

    /** La marca de cada entrada, con el icono y la etiqueta del diseño v4 (DEC-35). */
    public enum Marca {
        PROPIA("person", "Acción tuya"),
        CONSULTA("visibility", "Consulta autorizada"),
        DENEGADA("block", "Intento denegado"),
        SOLICITUD("playlist_add", "Solicitud de ampliación"),
        EMERGENCIA("emergency", "Acceso de emergencia");

        private final String icon;
        private final String etiqueta;

        Marca(String icon, String etiqueta) {
            this.icon = icon;
            this.etiqueta = etiqueta;
        }

        public String getIcon() {
            return icon;
        }

        public String getEtiqueta() {
            return etiqueta;
        }

        /** Para la clase de tono y el data-marca: «propia», «consulta», «denegada»… */
        public String getSlug() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Una entrada tal como la pinta la vista: quién, qué, cuándo y su marca. */
    public record Entrada(Long id, Marca marca, String quien, String que, String cuando) {
    }

    private final AuditRepository audit;
    private final PermissionRepository permissions;
    private final ProfessionalRepository professionals;
    private final Clock clock;

    public ActivityService(AuditRepository audit, PermissionRepository permissions,
                           ProfessionalRepository professionals, Clock clock) {
        this.audit = audit;
        this.permissions = permissions;
        this.professionals = professionals;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Entrada> deLaPaciente(Long patientId) {
        Instant now = clock.instant();
        // Los códigos salen de los permisos de esta misma paciente: una fila nunca nombra un permiso ajeno.
        Map<Long, String> codigos = permissions.findByPatientIdOrderByCreatedAtDescIdDesc(patientId).stream()
                .collect(Collectors.toMap(Permission::getId, Permission::getCode));
        Map<Long, String> nombres = new HashMap<>();
        return audit.findByPatientIdOrderByCreatedAtDescIdDesc(patientId).stream()
                .map(e -> entrada(e, codigos, nombres, now))
                .toList();
    }

    private Entrada entrada(AuditEntry e, Map<Long, String> codigos, Map<Long, String> nombres, Instant now) {
        String codigo = Optional.ofNullable(e.getPermissionId()).map(codigos::get).orElse("");
        String alcance = alcance(e);
        String detalle = e.getDetail();
        String cuando = PermissionService.cuando(e.getCreatedAt(), now, clock.getZone());
        return switch (e.getType()) {
            case PERMISO_CREADO -> new Entrada(e.getId(), Marca.PROPIA, "Tú",
                    "Generaste el código " + codigo + " para " + alcance, cuando);
            case PERMISO_REVOCADO -> new Entrada(e.getId(), Marca.PROPIA, "Tú",
                    "Revocaste el permiso " + codigo + " antes de que venciera", cuando);
            case REGISTRO_ELIMINADO -> new Entrada(e.getId(), Marca.PROPIA, "Tú",
                    detalle != null ? detalle : "Eliminaste un registro", cuando);
            case PERMISO_CANJEADO -> new Entrada(e.getId(), Marca.CONSULTA, profesional(e, nombres),
                    "Canjeó " + codigo + " para ver " + alcance, cuando);
            case CONSULTA -> new Entrada(e.getId(), Marca.CONSULTA, profesional(e, nombres),
                    (detalle != null ? detalle + " de " : "Vio ") + alcance, cuando);
            case CONSULTA_DENEGADA -> new Entrada(e.getId(), Marca.DENEGADA, profesional(e, nombres),
                    (detalle != null ? detalle : "Intentó ver lo que no le compartiste") + " · denegado", cuando);
            case SOLICITUD_AMPLIACION -> new Entrada(e.getId(), Marca.SOLICITUD, profesional(e, nombres),
                    (detalle != null ? detalle : "Pidió ampliar el permiso") + " al código " + codigo, cuando);
            case EMERGENCIA -> new Entrada(e.getId(), Marca.EMERGENCIA, profesional(e, nombres),
                    "Abrió tu ficha de emergencia" + (detalle != null ? " · " + detalle : ""), cuando);
        };
    }

    /** «Dr. Solís · Laboratorio Santa Ana». Cada profesional se busca una sola vez por página. */
    private String profesional(AuditEntry e, Map<Long, String> nombres) {
        if (e.getProfessionalId() == null) {
            return "Un profesional";
        }
        return nombres.computeIfAbsent(e.getProfessionalId(), id -> professionals.findById(id)
                .map(pro -> pro.getNombre() + " · " + pro.getInstitucion())
                .orElse("Un profesional"));
    }

    private static String alcance(AuditEntry e) {
        String[] nombres = e.getCategories();
        if (nombres == null) {
            return "nada";
        }
        return Category.join(Arrays.stream(nombres)
                .flatMap(n -> Arrays.stream(Category.values()).filter(c -> c.name().equals(n)))
                .toList());
    }
}
