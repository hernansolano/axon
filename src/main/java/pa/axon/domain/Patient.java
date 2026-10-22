package pa.axon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "paciente")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cedula", nullable = false, unique = true)
    private String cedula;

    @Column(name = "nombre", nullable = false)
    private String nombre;

    @Column(name = "hash_clave", nullable = false)
    private String hashClave;

    protected Patient() {
    }

    public Patient(String cedula, String nombre, String hashClave) {
        this.cedula = cedula;
        this.nombre = nombre;
        this.hashClave = hashClave;
    }

    public Long getId() {
        return id;
    }

    public String getCedula() {
        return cedula;
    }

    public String getNombre() {
        return nombre;
    }

    public String getHashClave() {
        return hashClave;
    }
}
