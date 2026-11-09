package kia.render;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kia.util.Carpetas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Archivos de los proyectos directo de Google Drive (igual que DriveDirecto de kia): si la cancion se agrego desde otro
 * equipo y aun no esta en esta maquina, se bajan de kia_proyectos/KEY con la cuenta de los colaboradores (parametros
 * drive_client_id / drive_client_secret / drive_refresh_token) y el indice proyecto_archivo. Solo baja.
 */
@Component
public class ProyectoDrive {

    private static final Logger log = LoggerFactory.getLogger(ProyectoDrive.class);

    private final Fila fila;
    private final String api = System.getProperty("kia.drive.api", "https://www.googleapis.com");
    private final String oauth = System.getProperty("kia.drive.oauth", "https://oauth2.googleapis.com/token");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private String token;
    private Instant vence = Instant.EPOCH;

    public ProyectoDrive(Fila fila) {
        this.fila = fila;
    }

    /** Baja de Drive los archivos que falten en la carpeta del proyecto (KEY). Regresa los que se bajaron. */
    public List<String> asegura(File carpeta, List<String> nombres, String etiqueta) throws IOException {
        List<String> bajados = new ArrayList<>();
        String key = carpeta.getName().toUpperCase();
        if (!Proyecto.esKey(key)) {
            return bajados;
        }
        for (String n : nombres) {
            File destino = new File(carpeta, n);
            if (destino.isFile() && destino.length() > 0) {
                continue;
            }
            if (baja(key, n, destino)) {
                bajados.add(n);
                log.info("Bajado de Drive {}/{} ({})", key, n, etiqueta);
            }
        }
        return bajados;
    }

    private boolean baja(String key, String nombre, File destino) throws IOException {
        Fila.ArchivoProyecto a;
        try {
            a = fila.archivoProyecto(key, nombre);
        } catch (SQLException e) {
            throw new IOException("No se pudo leer el índice de archivos de " + key + ": " + e.getMessage(), e);
        }
        if (a == null || a.borrado() || a.driveId() == null || a.driveId().isBlank()) {
            return false;
        }
        Path tmp = Files.createTempFile(Carpetas.temp().toPath(), "drive-", ".part");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/drive/v3/files/" + a.driveId() + "?alt=media"))
                    .header("Authorization", "Bearer " + token()).timeout(Duration.ofHours(1)).GET().build();
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                if (r.statusCode() != 200) {
                    throw new IOException("Google Drive " + r.statusCode() + " al bajar " + key + "/" + nombre + ": "
                            + new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (a.md5() != null && !a.md5().isBlank() && !a.md5().equalsIgnoreCase(HexFormat.of().formatHex(md.digest()))) {
                throw new IOException("La descarga de " + nombre + " llegó incompleta (md5 distinto)");
            }
            Carpetas.colocaArchivo(tmp.toFile(), destino);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Descarga interrumpida", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(vence.minusSeconds(60))) {
            return token;
        }
        String id, secreto, refresh;
        try {
            id = fila.parametro("drive_client_id");
            secreto = fila.parametro("drive_client_secret");
            refresh = fila.parametro("drive_refresh_token");
        } catch (SQLException e) {
            throw new IOException("No se pudo leer la cuenta de Drive de los proyectos: " + e.getMessage(), e);
        }
        if (vacio(id) || vacio(secreto) || vacio(refresh)) {
            throw new IOException("No está configurada la cuenta de Drive de los proyectos (parámetros drive_client_id / "
                    + "drive_client_secret / drive_refresh_token)");
        }
        String cuerpo = "grant_type=refresh_token&client_id=" + url(id.trim()) + "&client_secret=" + url(secreto.trim())
                + "&refresh_token=" + url(refresh.trim());
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(oauth))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build(), HttpResponse.BodyHandlers.ofString());
        String t = campo(r.body(), "access_token");
        if (r.statusCode() / 100 != 2 || t == null) {
            throw new IOException("Google no aceptó la cuenta de Drive de los proyectos: " + r.body());
        }
        String exp = campo(r.body(), "expires_in");
        token = t;
        vence = Instant.now().plusSeconds(exp == null ? 3600 : Long.parseLong(exp));
        return token;
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String campo(String json, String nombre) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(nombre) + "\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+))").matcher(json == null ? "" : json);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
    }
}
