package kia.render;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Carpeta de un proyecto del formato nuevo (PATH_MAIN/KEY), con los mismos nombres que ProyectoCancion de kia. */
public record Proyecto(File carpeta) {

    public static final String MP3 = "main.mp3";
    public static final String SONGINI = "songini.txt";
    public static final String KFN_ANTERIOR = "main.kfn";
    public static final String PORTADA = "portada.jpg";
    public static final String INSTRUMENTAL = "rv_instrumental.mp3";
    public static final String INSTRUMENTAL_SV = "rv_sv_instrumental.mp3";

    private static final Pattern KEY = Pattern.compile("[A-Z0-9]{5,8}");

    public static boolean esKey(String nombre) {
        return nombre != null && KEY.matcher(nombre.trim().toUpperCase(Locale.ROOT)).matches();
    }

    public static Proyecto deKey(String pathMain, String key) {
        return new Proyecto(new File(pathMain, key.trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * Ruta guardada en bitacora_proceso (en Windows: C:\...\karaoke_ia\KEY\songini.txt) -> ruta en este equipo.
     * Si ya existe tal cual se usa asi; si empieza con alguna de las raices conocidas se cambia por PATH_MAIN.
     */
    public static File resolver(String pathBitacora, String pathMain, List<String> alias) {
        if (pathBitacora == null || pathBitacora.isBlank()) {
            throw new IllegalArgumentException("El proceso no trae ruta");
        }
        File tal = new File(pathBitacora);
        if (tal.exists()) {
            return tal;
        }
        String texto = pathBitacora.replace('/', '\\');
        for (String raiz : alias) {
            String r = raiz.replace('/', '\\').replaceAll("\\\\+$", "");
            if (texto.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT) + "\\")) {
                File f = new File(pathMain);
                for (String parte : texto.substring(r.length() + 1).split("\\\\")) {
                    if (!parte.isEmpty()) {
                        f = new File(f, parte);
                    }
                }
                return f;
            }
        }
        // formato nuevo con otra raiz: .../<KEY>/archivo
        String[] partes = texto.split("\\\\");
        if (partes.length >= 2 && esKey(partes[partes.length - 2])) {
            return new File(new File(pathMain, partes[partes.length - 2].toUpperCase(Locale.ROOT)), partes[partes.length - 1]);
        }
        return tal;
    }

    public String key() {
        return carpeta.getName().toUpperCase(Locale.ROOT);
    }

    public boolean existe() {
        return new File(carpeta, MP3).isFile();
    }

    public File mp3() {
        return new File(carpeta, MP3);
    }

    /** songini.txt (en la base o el archivo); si el proyecto todavia trae el main.kfn de antes, ese. */
    public File songIni() {
        File s = new File(carpeta, SONGINI);
        File k = new File(carpeta, KFN_ANTERIOR);
        return !kia.claude.ProyectoSongIni.existe(s) && k.isFile() ? k : s;
    }

    public File portada() {
        return new File(carpeta, PORTADA);
    }

    public File[] instrumentales() {
        return new File[]{new File(carpeta, INSTRUMENTAL), new File(carpeta, INSTRUMENTAL_SV)};
    }

    /** "Artista - Nombre - Karaoke - id.mp4" y "... - Karaoke - sv - id.mp4" (asi se suben a Drive). */
    public File[] finales(Fila.Cancion c) {
        String tema = limpia(c.tema());
        return new File[]{new File(carpeta, tema + " - Karaoke - " + c.id() + ".mp4"),
            new File(carpeta, tema + " - Karaoke - sv - " + c.id() + ".mp4")};
    }

    /** Caracteres que Windows no acepta en nombres de archivo (la carpeta se sincroniza con Drive en Windows). */
    static String limpia(String nombre) {
        return nombre.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
    }
}
