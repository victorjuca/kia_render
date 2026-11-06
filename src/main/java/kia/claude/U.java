package kia.claude;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// ====================================================================================================
//  UTILIDADES Y CONSTANTES
// ====================================================================================================
final class U {
    static final double LEAD_P = 1.2, FADE_IN = .6, FADE_OUT = .5, GAP = 8.0, BAR_DELAY = .8; static double LINE_SP = 1.45;
    static final double MK_APPROACH = 1.3, MK_TRAVEL = .08, MK_HOLD = .45, MK_FADE = .15, ARROW_T = .95, ARROW_A = .0105;
    static final double HIT_X = .122, ARROW_X = .10, SPEED = .29, MAX_TEXT_W = .90;
    static final int SR = 44100;

    static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    static int clamp(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }
    static double r3(double x) { return Math.rint(x * 1000) / 1000.0; }
    static boolean has(double t) { return !Double.isNaN(t); }
    static String fmt(double t) {
        t = Math.max(0, t); int m = (int) (t / 60);
        return String.format(Locale.ROOT, "%02d:%06.3f", m, t - m * 60);
    }
    static String esc(String t) { return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    static Color hex(String t, Color d) {
        try { t = t.trim(); if (t.startsWith("#")) t = t.substring(1); return new Color(Integer.parseInt(t.substring(0, 2), 16), Integer.parseInt(t.substring(2, 4), 16), Integer.parseInt(t.substring(4, 6), 16)); }
        catch (Exception e) { return d; }
    }
    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }
    static Color withAlpha(String hex, String aHex) {
        Color c = hex(hex, Color.BLACK); int a = 255;
        try { a = Integer.parseInt(aHex == null || aHex.isEmpty() ? "FF" : aHex, 16); } catch (Exception ignored) { }
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Busca un ejecutable en el PATH (ffmpeg, ffprobe). */
    static File which(String name) {
        String path = System.getenv("PATH"); if (path == null) return null;
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (String d : path.split(File.pathSeparator)) {
            File f = new File(d.replace("\"", ""), win ? name + ".exe" : name);
            if (f.isFile()) return f;
        }
        return null;
    }

    // ---- fuentes ----
    static final String[] FONT_FALLBACKS = {"C:/Windows/Fonts/ariblk.ttf", "C:/Windows/Fonts/arialbd.ttf", "/System/Library/Fonts/Supplemental/Arial Black.ttf",
        "/usr/share/fonts/truetype/google-fonts/Poppins-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"};
    private static final Map<String, Font> FONT_CACHE = new HashMap<>();
    static String norm(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]", ""); }

    static File findFontFile(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        File f = new File(spec); if (f.isFile()) return f;
        String key = norm(spec.split("\\*")[0]);
        Map<String, String> alias = Map.of("arialblack", "ariblk", "arial", "arial", "impact", "impact");
        String al = alias.get(key);
        String home = System.getProperty("user.home");
        String[] dirs = {"C:/Windows/Fonts", home + "/AppData/Local/Microsoft/Windows/Fonts", "/usr/share/fonts", "/usr/local/share/fonts", home + "/.fonts",
            home + "/.local/share/fonts", "/System/Library/Fonts", "/System/Library/Fonts/Supplemental", "/Library/Fonts"};
        File best = null; long bestScore = Long.MAX_VALUE;
        for (String d : dirs) {
            File dir = new File(d); if (!dir.isDirectory()) continue;
            try (var st = Files.walk(dir.toPath(), 6)) {
                for (var p : (Iterable<java.nio.file.Path>) st::iterator) {
                    String fn = p.getFileName().toString().toLowerCase();
                    if (!(fn.endsWith(".ttf") || fn.endsWith(".otf"))) continue;
                    String n = norm(fn.substring(0, fn.lastIndexOf('.')));
                    if (n.equals(key) || (al != null && n.equals(al)) || n.contains(key)) {
                        long score = ((!n.equals(key) && !(al != null && n.equals(al))) ? 1_000_000 : 0) + (n.contains("bold") ? 0 : 100_000) + n.length();
                        if (score < bestScore) { bestScore = score; best = p.toFile(); }
                    }
                }
            } catch (Exception ignored) { }
        }
        return best;
    }
    static File fontFileFor(String name) {
        File f = findFontFile(name); if (f != null) return f;
        for (String s : FONT_FALLBACKS) if (new File(s).isFile()) return new File(s);
        return null;
    }
    /** Fuente base (tamaño 1) a partir del nombre; si no hay archivo, una fuente logica en negrita. */
    static Font baseFont(String name) {
        return FONT_CACHE.computeIfAbsent(name == null ? "" : name, k -> {
            File f = fontFileFor(k);
            if (f != null) { try { return Font.createFont(Font.TRUETYPE_FONT, f).deriveFont(1f); } catch (Exception ignored) { } }
            return new Font(Font.SANS_SERIF, Font.BOLD, 1);
        });
    }
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    static double adv(Font f, String s) { return s.isEmpty() ? 0 : new TextLayout(s, f, FRC).getAdvance(); }
    /** Caracteres que caben en una linea del karaoke con la fuente de la pista: la guia naranja del editor y el limite al acomodar la letra. */
    static int caracteresPorLinea(Props q) {
        try {
            Font f = baseFont(q.font).deriveFont((float) Math.max(10, Math.round(q.fsize * 360 / 400.0))); String sample = "el amor que te di fue tan grande como el cielo azul de mayo";
            double avg = adv(f, sample) / sample.length(); return Math.max(5, (int) (640 * MAX_TEXT_W / avg));
        } catch (Exception e) { return 40; }
    }

    // ---- ffmpeg ----
    static byte[] runFfmpeg(List<String> cmd, byte[] stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread te = new Thread(() -> { try { p.getErrorStream().transferTo(err); } catch (IOException ignored) { } }); te.setDaemon(true); te.start();
        Thread tw = new Thread(() -> { try (OutputStream os = p.getOutputStream()) { if (stdin != null) os.write(stdin); } catch (IOException ignored) { } }); tw.setDaemon(true); tw.start();
        byte[] out = p.getInputStream().readAllBytes();
        int rc = p.waitFor(); te.join(1000);
        if (rc != 0 || out.length == 0) throw new IOException("ffmpeg: " + err.toString(StandardCharsets.UTF_8).trim());
        return out;
    }
}
