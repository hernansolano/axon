package pa.axon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "profesional")
public class Professional {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idoneidad", nullable = false, unique = true)
    private String idoneidad;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "institucion", nullable = false)
    private String institucion;

    @Column(name = "hash_clave", nullable = false)
    private String hashClave;

    protected Professional() {
    }

    public Professional(String idoneidad, String nombre, String institucion, String hashClave) {
        this.idoneidad = idoneidad;
        this.nombre = nombre;
        this.institucion = institucion;
        this.hashClave = hashClave;
    }

    public Long getId() {
        return id;
    }

    public String getIdoneidad() {
        return idoneidad;
    }

    public String getNombre() {
        return nombre;
    }

    public String getInstitucion() {
        return institucion;
    }

    public String getHashClave() {
        return hashClave;
    }
}
