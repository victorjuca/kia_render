package kia.render;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import kia.claude.MotorRender;
import kia.util.Carpetas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * El proceso "Renderizar KFN" de la barredora (RenderizarService.renderizarCancion de kia) para proyectos
 * del formato nuevo (PATH_MAIN/KEY): video base con el motor de Karaoke Studio, portada, versiones Limpia
 * (rv_instrumental.mp3) y Segunda Voz (rv_sv_instrumental.mp3), y subida de las dos a Drive.
 * Todo lo intermedio se hace en KIA_TEMP; a la carpeta del proyecto solo llegan los dos videos terminados.
 */
@Service
public class RenderService {

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);

    private final Fila fila;
    private final FFmpeg ff;
    private final Portada portada;
    private final Drive drive;
    private final RenderProperties cfg;
    private final SongIniBase songIni;

    public RenderService(Fila fila, FFmpeg ff, Portada portada, Drive drive, RenderProperties cfg, SongIniBase songIni) {
        this.songIni = songIni;
        this.fila = fila;
        this.ff = ff;
        this.portada = portada;
        this.drive = drive;
        this.cfg = cfg;
    }

    public void renderiza(Fila.Proceso p, Consumer<String> progreso, BooleanSupplier cancelado) throws Exception {
        Proyecto proy = ubica(p);
        Fila.Cancion c = fila.cancionPorKey(proy.key())
                .or(() -> porId(p.cancionId()))
                .orElseThrow(() -> new IOException("No se encontró en la base la canción con key " + proy.key()));
        File[] finales = proy.finales(c);

        if (completos(finales) && (alDia(proy, finales) || !urlsCompletas(c))) {
            // ya se habian generado (p. ej. fallo la subida) y nada del proyecto cambio despues: solo se suben a Drive.
            // Para forzar otro render se borran los dos videos de la carpeta del proyecto.
            progreso.accept("Los videos ya existen y están al día; solo se suben a Drive");
        } else {
            genera(proy, c, finales, progreso, cancelado);
        }

        if (!cfg.isDriveHabilitado()) {
            log.warn("DRIVE_HABILITADO=false: los videos quedan en {} sin subirse a Drive", proy.carpeta());
            return;
        }
        List<String> errores = new ArrayList<>();
        int[] tipos = {Fila.URL_LIMPIA, Fila.URL_SEGUNDA_VOZ};
        for (int i = 0; i < 2; i++) {
            progreso.accept("Subiendo a Drive: " + finales[i].getName());
            try {
                drive.sube(finales[i], c.id(), tipos[i]);
            } catch (IOException | SQLException e) {
                errores.add(finales[i].getName() + ": " + e.getMessage());
            }
        }
        if (!errores.isEmpty()) {
            throw new IOException("No se pudo completar la subida a Drive (los videos quedan en el proyecto para reintentar sin renderizar). "
                    + String.join(" | ", errores));
        }
    }

    private void genera(Proyecto proy, Fila.Cancion c, File[] finales, Consumer<String> progreso, BooleanSupplier cancelado) throws Exception {
        File songIni = proy.songIni();
        if (!kia.claude.ProyectoSongIni.existe(songIni)) {
            throw new IOException("La canción " + proy.key() + " todavía no tiene Song.ini (letra) en la base");
        }
        String ini = MotorRender.songIni(songIni);
        if (Portada.segundoInicio(ini) <= 0) {
            throw new IOException("No se ha iniciado la digitación en el proyecto " + proy.key());
        }
        File[] audios = proy.instrumentales();
        for (File a : audios) {
            if (!a.isFile() || a.length() == 0) {
                throw new IOException("Falta la pista " + a.getName() + ". Ejecuta Remover voz antes de renderizar.");
            }
        }
        Set<String> fuentes = Recursos.fuentesFaltantes(ini);
        if (!fuentes.isEmpty()) {
            log.warn("Fuentes que no están instaladas (se usará otra): {}", fuentes);
        }

        Path trabajo = Files.createTempDirectory(Carpetas.temp().toPath(), "render-" + proy.key() + "-");
        try {
            File base = trabajo.resolve("base.mp4").toFile();
            if (!MotorRender.renderizarMp4(songIni, base, progreso, cancelado) || !base.isFile() || base.length() == 0) {
                throw new IOException(cancelado.getAsBoolean() ? "Render cancelado" : "El motor no pudo generar el MP4 de " + proy.key());
            }
            progreso.accept("Agregando portada");
            Portada.Resultado principal = portada.aplica(base, proy.portada(), portada.esJoin(ini),
                    trabajo.toFile(), trabajo.resolve("principal.mp4").toFile());
            Files.deleteIfExists(base.toPath());
            File[] versiones = {trabajo.resolve("limpia.mp4").toFile(), trabajo.resolve("sv.mp4").toFile()};
            for (int i = 0; i < 2; i++) {
                progreso.accept("Generando " + (i == 0 ? "Limpia" : "Segunda Voz"));
                reemplazaAudio(principal, audios[i], versiones[i]);
            }
            progreso.accept("Moviendo Limpia y Segunda Voz a la carpeta del proyecto");
            for (int i = 0; i < 2; i++) {
                Carpetas.colocaArchivo(versiones[i], finales[i]);
            }
        } finally {
            borra(trabajo);
        }
    }

    /** Copia el video y pone la pista instrumental recorrida lo que dura el clip de portada (RenderizarService.reemplazarAudio). */
    void reemplazaAudio(Portada.Resultado principal, File audio, File salida) throws IOException {
        double retraso = principal.desplazamiento();
        double duracion = principal.duracion();
        if (ff.duracion(audio) + retraso < duracion - 0.5) {
            throw new IOException("La pista instrumental es más corta que la canción: " + audio.getName());
        }
        String filtro = "[1:a:0]asetpts=PTS-STARTPTS,adelay=" + Math.round(retraso * 1000)
                + ":all=1,apad,atrim=duration=" + String.format(Locale.ROOT, "%.3f", duracion) + "[a]";
        ff.ejecuta("-y", "-i", principal.video().getAbsolutePath(), "-i", audio.getAbsolutePath(),
                "-filter_complex", filtro, "-c:v", "copy", "-map", "0:v:0", "-map", "[a]", "-c:a", "aac", "-b:a", "320k",
                "-shortest", "-movflags", "+faststart", salida.getAbsolutePath());
        if (Math.abs(ff.duracion(salida) - duracion) > 0.2) {
            throw new IOException("La duración de " + salida.getName() + " no coincide con el video principal.");
        }
    }

    /** Carpeta del proyecto: la de la ruta de la bitacora (…/KEY/archivo) o, si no, la key de la cancion_id. */
    Proyecto ubica(Fila.Proceso p) throws IOException, SQLException {
        File archivo = Proyecto.resolver(p.path(), cfg.getPathMain(), cfg.getPathMainAlias());
        File carpeta = archivo.isDirectory() ? archivo : archivo.getParentFile();
        if (carpeta != null && Proyecto.esKey(carpeta.getName())) {
            Proyecto proy = new Proyecto(carpeta);
            if (proy.existe()) {
                return proy;
            }
            Proyecto local = Proyecto.deKey(cfg.getPathMain(), carpeta.getName());
            if (local.existe()) {
                return local;
            }
        }
        if (p.cancionId() != null && p.cancionId() > 0) {
            var c = fila.cancionPorId(p.cancionId());
            if (c.isPresent() && Proyecto.esKey(c.get().key())) {
                Proyecto proy = Proyecto.deKey(cfg.getPathMain(), c.get().key());
                if (proy.existe()) {
                    return proy;
                }
            }
        }
        throw new IOException("No se encontró el proyecto (formato KARAOKE IA/KEY con main.mp3) de " + p.path()
                + ". Los proyectos del formato anterior se renderizan desde kia.");
    }

    private java.util.Optional<Fila.Cancion> porId(Integer id) {
        try {
            return id == null ? java.util.Optional.empty() : fila.cancionPorId(id);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean urlsCompletas(Fila.Cancion c) throws SQLException {
        return lleno(fila.url(c.id(), Fila.URL_LIMPIA)) && lleno(fila.url(c.id(), Fila.URL_SEGUNDA_VOZ));
    }

    private static boolean lleno(String s) {
        return s != null && !s.isBlank();
    }

    /** true si los dos videos son mas nuevos que todo lo que entra al render (Song.ini, audios, portada y fondo). */
    boolean alDia(Proyecto proy, File[] finales) {
        long videos = Math.min(finales[0].lastModified(), finales[1].lastModified());
        if (songIni.actualizado(proy.carpeta()) > videos) {
            return false;    // la letra (Song.ini en la base) cambio despues de los videos
        }
        File[] entradas = {proy.mp3(), proy.portada(), new File(proy.carpeta(), "fondo.png"),
            proy.instrumentales()[0], proy.instrumentales()[1]};
        for (File e : entradas) {
            if (e.isFile() && e.lastModified() > videos) {
                return false;
            }
        }
        return true;
    }

    private static boolean completos(File[] f) {
        return f[0].isFile() && f[0].length() > 0 && f[1].isFile() && f[1].length() > 0;
    }

    private static void borra(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
        } catch (IOException e) {
            log.warn("No se pudo borrar el temporal {}", dir, e);
        }
    }
}
