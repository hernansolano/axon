package pa.axon.domain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Los archivos de los documentos, en una carpeta fuera de lo que el servidor publica. Cada archivo
 * se llama por el UUID de {@code ruta_almacen}, sin el nombre original ni la extensión: no hay
 * dirección que adivinar ni archivo que se sirva sin pasar por /documento/{id} (RS-13).
 */
@Component
public class DocumentStorage {

    private final Path root;

    public DocumentStorage(@Value("${axon.almacen.dir:almacen}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public void save(UUID storagePath, byte[] content) {
        try {
            Files.createDirectories(root);
            Files.write(file(storagePath), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Vacío si el archivo ya no está en el disco: el registro sigue, el visor muestra el error. */
    public Optional<byte[]> read(UUID storagePath) {
        try {
            return Optional.of(Files.readAllBytes(file(storagePath)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Si el archivo no existe, no hace nada. */
    public void delete(UUID storagePath) {
        try {
            Files.deleteIfExists(file(storagePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(UUID storagePath) {
        // UUID.toString() solo produce hexadecimales y guiones: no puede salir de la carpeta.
        return root.resolve(storagePath.toString());
    }
}
