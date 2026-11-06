package kia.render;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** ffmpeg con lista de argumentos (sin cmd.exe), para que funcione igual en Windows y en Linux/Docker. */
@Component
public class FFmpeg {

    private static final Pattern DURACION = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)");

    private final String bin;

    public FFmpeg(RenderProperties cfg) {
        this.bin = cfg.getFfmpeg();
    }

    public void ejecuta(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder salida = new StringBuilder();
        try (BufferedReader r = p.inputReader(StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                salida.append(l).append('\n');
                if (salida.length() > 6000) {
                    salida.delete(0, salida.length() - 6000);
                }
            }
        }
        try {
            if (p.waitFor() != 0) {
                throw new IOException("Falló ffmpeg: " + ultimas(salida.toString(), 6));
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg interrumpido", e);
        }
    }

    /** Duracion en segundos leyendo "Duration:" de ffmpeg -i (igual que PortadaService.duracion de kia). */
    public double duracion(File archivo) throws IOException {
        Process p = new ProcessBuilder(bin, "-hide_banner", "-nostdin", "-i", archivo.getAbsolutePath())
                .redirectErrorStream(true).start();
        String texto = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            p.waitFor();   // sin salida ffmpeg regresa 1 despues de describir la entrada
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Lectura de duración interrumpida", e);
        }
        Matcher m = DURACION.matcher(texto);
        if (!m.find()) {
            throw new IOException("No se pudo leer la duración de " + archivo);
        }
        double s = Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Double.parseDouble(m.group(3));
        if (s <= 0) {
            throw new IOException("Duración inválida: " + archivo);
        }
        return s;
    }

    private static String ultimas(String texto, int n) {
        String[] l = texto.strip().split("\n");
        return String.join(" | ", List.of(l).subList(Math.max(0, l.length - n), l.length));
    }
}
