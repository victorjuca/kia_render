package kia.render;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kia.form.PropertiesForm;
import kia.util.Carpetas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Arranque: rutas, recursos del motor, identificador de la maquina y fuentes instaladas. */
public final class Recursos {

    private static final Logger log = LoggerFactory.getLogger(Recursos.class);
    private static final Pattern FONT = Pattern.compile("(?m)^\\s*Font\\s*=\\s*([^*\\r\\n]+)");

    private Recursos() {
    }

    /** Prepara el motor copiado de kia (PropertiesForm / Carpetas) con la configuracion del servicio. */
    public static List<String> configura(RenderProperties cfg) {
        List<String> faltan = new ArrayList<>();
        if (cfg.getMaquinaId().isEmpty() && esWindows()) {
            cfg.setMaquinaId(idMaquinaWindows());
        }
        if (cfg.getMaquinaId().isEmpty()) {
            faltan.add("WORKER_MAQUINA_ID");
        }
        if (!new File(cfg.getPathMain()).isDirectory()) {
            faltan.add("PATH_MAIN (" + cfg.getPathMain() + " no existe)");
        }
        File temp = new File(cfg.getKiaTemp());
        temp.mkdirs();
        Carpetas.configura(temp);
        File recursos = new File(cfg.getRecursosDir()).getAbsoluteFile();
        PropertiesForm.FILE_IMG_CON_FONDO_IA = new File(recursos, "Fondo Karaoke IA.jpg").getPath();
        PropertiesForm.FILE_IMG_SIN_FONDO_IA = new File(recursos, "Fondo Karaoke IA_sin_logo.jpg").getPath();
        PropertiesForm.FILE_SIN_AUDIO = new File(recursos, "sin_audio.mp3").getPath();
        PropertiesForm.setWhisperProbabilidad(cfg.getWhisperProbabilidad());
        for (String r : List.of(PropertiesForm.FILE_IMG_CON_FONDO_IA, PropertiesForm.FILE_IMG_SIN_FONDO_IA, PropertiesForm.FILE_SIN_AUDIO)) {
            if (!new File(r).isFile()) {
                faltan.add("RECURSOS_DIR (" + r + " no existe)");
            }
        }
        return faltan;
    }

    /** Fuentes que pide el Song.ini y no estan instaladas (el render usaria otra y se veria distinto). */
    public static Set<String> fuentesFaltantes(String songIni) {
        Set<String> instaladas = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .map(f -> f.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> faltan = new TreeSet<>();
        Matcher m = FONT.matcher(songIni == null ? "" : songIni);
        while (m.find()) {
            String f = m.group(1).trim();
            if (!f.isEmpty() && !instaladas.contains(f.toLowerCase(Locale.ROOT))) {
                faltan.add(f);
            }
        }
        return faltan;
    }

    static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** El mismo identificador que kia (OSHI: UUID de Win32_ComputerSystemProduct). */
    private static String idMaquinaWindows() {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_ComputerSystemProduct).UUID").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(20, TimeUnit.SECONDS);
            return s;
        } catch (IOException e) {
            log.warn("No se pudo leer el identificador de la maquina", e);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
