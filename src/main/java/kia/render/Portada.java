package kia.render;

import java.io.File;
import java.io.IOException;
import kia.form.PropertiesForm;
import org.springframework.stereotype.Component;

/**
 * Portada del video (PortadaService.aplicarPortada de kia con los comandos *_JAVA de comand.properties):
 * si la letra empieza tarde se sobrepone la portada los primeros 5 s (join); si no, se agrega un clip
 * de portada de 4 s al inicio (concat) y el audio de las versiones se recorre esa duracion.
 */
@Component
public class Portada {

    /** Video con portada, segundos que se recorre el audio y duracion total. */
    public record Resultado(File video, double desplazamiento, double duracion) {
    }

    private final FFmpeg ff;
    private final RenderProperties cfg;

    public Portada(FFmpeg ff, RenderProperties cfg) {
        this.ff = ff;
        this.cfg = cfg;
    }

    /** true si la primera marca (Sync0) es igual o mayor a segundo_inicio_cancion_join (KFNDumper.validaSongIni). */
    public boolean esJoin(String songIni) {
        return segundoInicio(songIni) >= cfg.getSegundoInicioJoin();
    }

    /** Las marcas Sync van en centesimas de segundo. */
    static int segundoInicio(String songIni) {
        for (String l : songIni.split("\\R")) {
            String t = l.trim();
            if (t.startsWith("Sync0=")) {
                try {
                    long cs = Long.parseLong(t.substring(6).split(",")[0].trim());
                    return (int) Math.ceil(cs / 100.0);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    public Resultado aplica(File base, File portada, boolean join, File trabajo, File salida) throws IOException {
        if (!portada.isFile()) {
            throw new IOException("No existe la portada: " + portada);
        }
        double desplazamiento = 0;
        if (join) {
            File mini = new File(trabajo, "mini_YT.mp4");
            ff.ejecuta("-y", "-loop", "1", "-i", portada.getAbsolutePath(), "-t", "3", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", mini.getAbsolutePath());
            ff.ejecuta("-y", "-i", base.getAbsolutePath(), "-i", mini.getAbsolutePath(), "-filter_complex",
                    "[1:v][0:v]scale2ref[portada][video];[portada]setsar=1[p];[video][p]overlay=0:0:enable='between(t,0,5)'",
                    "-c:a", "copy", salida.getAbsolutePath());
        } else {
            File clip = new File(trabajo, "mini_YT_audio.mp4");
            ff.ejecuta("-y", "-loop", "1", "-i", portada.getAbsolutePath(), "-i", PropertiesForm.FILE_SIN_AUDIO,
                    "-t", "4", "-r", "29.97", "-crf", "23", "-c:v", "libx264", "-tune", "stillimage", "-c:a", "aac",
                    "-b:a", "320k", "-pix_fmt", "yuv420p", "-shortest", clip.getAbsolutePath());
            desplazamiento = ff.duracion(clip);
            ff.ejecuta("-y", "-i", clip.getAbsolutePath(), "-i", base.getAbsolutePath(), "-filter_complex",
                    "[0:v][1:v]scale2ref[portada][video];[portada]setsar=1[p];[video]setsar=1[v];"
                    + "[p][0:a:0][v][1:a:0]concat=n=2:v=1:a=1[outv][outa]",
                    "-map", "[outv]", "-map", "[outa]", salida.getAbsolutePath());
        }
        if (!salida.isFile() || salida.length() == 0) {
            throw new IOException("No se generó el MP4 principal con portada");
        }
        return new Resultado(salida, desplazamiento, ff.duracion(salida));
    }
}
