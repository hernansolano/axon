package pa.axon.domain;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Alta de la cuenta de una paciente (HU-13, DEC-16). Recibe datos ya validados por el formulario.
 * No abre sesión: la paciente entra después por el login, como todas.
 */
@Service
public class PatientRegistrationService {

    /** Solo dos salidas: quien llama no sabe, ni debe saber, si la cédula ya existía (RS-14). */
    public enum Resultado { CREADA, NO_CREADA }

    private final PatientRepository patients;
    private final PasswordEncoder encoder;

    public PatientRegistrationService(PatientRepository patients, PasswordEncoder encoder) {
        this.patients = patients;
        this.encoder = encoder;
    }

    public Resultado registrar(String cedula, String nombre, String clave) {
        if (patients.existsByCedula(cedula)) {
            return Resultado.NO_CREADA;
        }
        try {
            patients.save(new Patient(cedula, nombre, encoder.encode(clave)));
        } catch (DataIntegrityViolationException e) {
            // Dos registros simultáneos con la misma cédula: la restricción UNIQUE decide.
            return Resultado.NO_CREADA;
        }
        return Resultado.CREADA;
    }
}
