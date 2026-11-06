package kia.render;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Subida a Drive con el CLI gdrive (igual que DriveService de kia): sube a la carpeta de tipo_url.id_drive,
 * registra el nuevo id con spi_cancion_url y despues borra el archivo anterior.
 */
@Component
public class Drive {

    private static final Logger log = LoggerFactory.getLogger(Drive.class);
    private static final Pattern ID = Pattern.compile("(?m)^\\s*Id:\\s*([a-zA-Z0-9_-]+)\\s*$");

    private final Fila fila;
    private final RenderProperties cfg;

    public Drive(Fila fila, RenderProperties cfg) {
        this.fila = fila;
        this.cfg = cfg;
    }

    public void sube(File archivo, int cancionId, int tipoUrl) throws IOException, SQLException {
        if (cancionId <= 0 || !archivo.isFile() || archivo.length() == 0) {
            throw new IOException("El archivo está vacío, no existe o el ID de canción no es válido: " + archivo.getName());
        }
        String anterior = fila.url(cancionId, tipoUrl);
        String carpeta = fila.carpetaDrive(tipoUrl);
        if (carpeta == null || carpeta.isBlank()) {
            throw new IOException("No se configuró la carpeta de Drive (tipo_url.id_drive) para el tipo " + tipoUrl);
        }
        String r = ejecuta(List.of(cfg.getGdrive(), "files", "upload", "--parent", carpeta, archivo.getAbsolutePath()));
        Matcher m = ID.matcher(r);
        if (!m.find()) {
            throw new IOException("Drive no devolvió el ID del archivo subido: " + r.strip());
        }
        String nuevo = m.group(1);
        Fila.Retorno reg = fila.guardaUrl(cancionId, nuevo, tipoUrl);
        if (!reg.ok()) {
            throw new IOException("No se pudo registrar la URL del archivo subido a Drive (" + nuevo + "): " + reg.descripcion());
        }
        if (anterior != null && !anterior.isBlank() && !anterior.equals(nuevo)) {
            try {
                ejecuta(List.of(cfg.getGdrive(), "files", "delete", anterior));
            } catch (IOException e) {
                log.warn("Se subió {} pero no se pudo borrar el archivo anterior de Drive {}", archivo.getName(), anterior, e);
            }
        }
    }

    private String ejecuta(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try {
            String r = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IOException("Falló gdrive: " + r.strip());
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Subida interrumpida", e);
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
