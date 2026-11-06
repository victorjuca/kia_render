package kia.claude;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Proyecto de karaoke SIN contenedor .kfn (formato KARAOKE IA/KEY):
 * <ul>
 * <li>songini.txt: el Song.ini (letra, marcas de tiempo, estilos, fondo).</li>
 * <li>main.mp3 (o la pista que diga Source) suelto en la carpeta: ya no se duplica dentro de un .kfn.</li>
 * <li>Los fondos de la app (Fondo Karaoke IA...) se toman de su carpeta; otras imagenes quedan en la del proyecto.</li>
 * </ul>
 * Para el estudio y el render es transparente: {@link #leer} arma en memoria lo mismo que traia el .kfn.
 */
public final class ProyectoSongIni {

    public static final String SONGINI = "songini.txt";
    public static final String MAIN = "main.mp3";
    /** Nombre del contenedor anterior de los proyectos nuevos (se separa al encontrarlo). */
    public static final String KFN_ANTERIOR = "main.kfn";
    /**
     * Fondo propio del proyecto. Los de siempre (con logo / sin logo) viven en kia/source y no se copian;
     * solo si se cambia el fondo de una cancion se guarda aqui, siempre con este nombre.
     */
    public static final String FONDO = "fondo.png";

    private ProyectoSongIni() {
    }

    public static boolean esSongIni(File f) {
        return f != null && f.getName().equalsIgnoreCase(SONGINI);
    }

    // ------------------------------------------------------------------ almacen (base de datos)

    /** Song.ini guardado y la version con que se leyo. */
    public static final class Dato {
        public final String texto;
        public final int version;

        public Dato(String texto, int version) {
            this.texto = texto;
            this.version = version;
        }
    }

    /**
     * Donde vive el Song.ini de los proyectos del formato nuevo (la carpeta es la key de la cancion). Con un
     * almacen (la base de datos) el songini.txt ya no hace falta: se lee y se guarda ahi. Sin almacen, archivo.
     */
    public interface Almacen {
        /** El Song.ini del proyecto de esa carpeta, o null si no hay. */
        Dato lee(File carpeta) throws IOException;

        /** Guarda el Song.ini (revisa que nadie haya guardado otra version despues de leerlo). */
        void guarda(File carpeta, String iniTxt) throws IOException;

        /** true si el proyecto de esa carpeta ya tiene Song.ini (rapido: para listas). */
        boolean existe(File carpeta);
    }

    private static volatile Almacen almacen;

    public static void usaAlmacen(Almacen a) {
        almacen = a;
    }

    public static Almacen almacen() {
        return almacen;
    }

    private static File carpetaDe(File songini) {
        return songini.getAbsoluteFile().getParentFile();
    }

    /**
     * Si el proyecto tiene Song.ini: en la base (almacen) o el archivo. Para cualquier otro archivo
     * (un .kfn) es lo mismo que isFile().
     */
    public static boolean existe(File f) {
        if (f == null) {
            return false;
        }
        Almacen a = almacen;
        if (a != null && esSongIni(f) && a.existe(carpetaDe(f))) {
            return true;
        }
        return f.isFile();
    }

    /**
     * Lo que se guarda junto al Song.ini para no abrirlo en las listas: {Sync0 en centesimas (o -1), total de
     * marcas de tiempo}. Igual que KFNDumper.validaSongIni: digitado = Sync0 mayor que 0.
     */
    public static int[] resumen(String iniTxt) {
        int inicio = -1, marcas = 0;
        if (iniTxt != null) {
            for (String l : iniTxt.split("\\R")) {
                String t = l.trim();
                if (!t.startsWith("Sync") || t.indexOf('=') < 0) {
                    continue;
                }
                String[] v = t.substring(t.indexOf('=') + 1).split(",");
                for (String x : v) {
                    if (!x.isBlank()) {
                        marcas++;
                    }
                }
                if (t.startsWith("Sync0=")) {
                    try {
                        inicio = Integer.parseInt(v[0].trim());
                    } catch (NumberFormatException e) {
                        inicio = -1;
                    }
                }
            }
        }
        return new int[]{inicio, marcas};
    }

    // ------------------------------------------------------------------ lectura

    /** Texto del Song.ini de un proyecto: songini.txt o el Song.ini dentro de un .kfn. */
    public static String leeSongIni(File f) throws IOException {
        if (esSongIni(f)) {
            return texto(f);
        }
        Kfn.Parsed p = Kfn.parse(Files.readAllBytes(f.toPath()));
        for (Kfn.Entry e : p.ents) {
            if (e.name.equalsIgnoreCase("song.ini")) {
                return new String(e.data, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("El .kfn no trae Song.ini: " + f.getName());
    }

    /**
     * Texto del songini del proyecto. Con almacen se lee de la base; si en la base no esta (o solo esta la copia
     * vieja, version 0) y todavia existe el songini.txt, se toma el archivo y se sube (migracion al vuelo).
     */
    public static String texto(File f) throws IOException {
        Almacen a = almacen;
        if (a == null || !esSongIni(f)) {
            return textoArchivo(f);
        }
        File dir = carpetaDe(f);
        Dato d = a.lee(dir);
        if (d != null && (d.version > 0 || !f.isFile())) {
            return d.texto;
        }
        if (f.isFile()) {
            String s = textoArchivo(f);
            try {
                a.guarda(dir, s);
            } catch (IOException e) {
                java.util.logging.Logger.getLogger(ProyectoSongIni.class.getName()).log(java.util.logging.Level.WARNING,
                        "No se pudo subir a la base el songini.txt de " + dir.getName(), e);
            }
            return s;
        }
        throw new java.io.FileNotFoundException("La canción " + dir.getName() + " todavía no tiene Song.ini");
    }

    private static String textoArchivo(File f) throws IOException {
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** Arma en memoria el contenido equivalente al .kfn: Song.ini, pista de audio e imagenes. */
    static Kfn.Parsed leer(File songini) throws IOException {
        File dir = songini.getAbsoluteFile().getParentFile();
        String ini = texto(songini);
        List<Ini.Sec> secs = Ini.parse(ini);
        Ini.Sec gen = null;
        for (Ini.Sec s : secs) {
            if (s.name.equals("General")) {
                gen = s;
                break;
            }
        }
        Kfn.Parsed p = new Kfn.Parsed();
        p.header = Kfn.defaultHeader();
        // imagenes: los fondos de la app y el fondo propio del proyecto (si esta en su carpeta)
        List<String> vistas = new ArrayList<>();
        for (File g : imagenesGlobales()) {
            p.ents.add(new Kfn.Entry(g.getName(), 3, Files.readAllBytes(g.toPath())));
            vistas.add(clave(g.getName()));
        }
        Ini.Sec bg = Ini.findBg(secs);
        String lib = bg != null ? Ini.get(bg, "LibImage") : "";
        if (!lib.isBlank() && !vistas.contains(clave(lib))) {
            File img = buscaEn(dir, lib);
            if (img == null) {
                img = buscaEn(dir, FONDO);
            }
            if (img != null) {
                p.ents.add(new Kfn.Entry(lib, 3, Files.readAllBytes(img.toPath())));
            }
        }
        // audio: el de Source (si esta en la carpeta) o main.mp3
        String[] sp = Ini.get(gen, "Source").split(",", 3);
        String src = sp.length >= 3 ? sp[2].trim() : "";
        File audio = src.isEmpty() ? null : buscaEn(dir, src);
        if (audio == null) {
            audio = new File(dir, MAIN).isFile() ? new File(dir, MAIN) : null;
        }
        if (audio != null) {
            p.ents.add(new Kfn.Entry(audio.getName(), 2, Files.readAllBytes(audio.toPath())));
            for (Kfn.Tag t : p.header) {
                if (t.tag.equals("SORC")) {
                    t.bytes = ("1,I," + audio.getName()).getBytes(StandardCharsets.UTF_8);
                }
            }
            if (!audio.getName().equals(src) && gen != null) {
                Ini.set(gen, "Source", "1,I," + audio.getName());
                ini = Ini.serialize(secs);
            }
        }
        p.ents.add(new Kfn.Entry("Song.ini", 1, ini.getBytes(StandardCharsets.UTF_8)));
        return p;
    }

    // ------------------------------------------------------------------ escritura

    /**
     * Guarda el proyecto: songini.txt y, solo si no estan ya iguales en la carpeta, las pistas de audio
     * y las imagenes que no son fondos de la app.
     */
    static void guardar(File songini, String iniTxt, List<Studio.Extra> audios, String bgName, byte[] bgBytes,
            List<Studio.Extra> imagenes) throws IOException {
        File dir = songini.getAbsoluteFile().getParentFile();
        for (Studio.Extra a : audios) {
            escribeSiCambia(new File(dir, a.name), a.data);
        }
        // solo se guarda el fondo si es propio (fondo.png); los de kia/source no se copian
        if (bgBytes != null && bgName != null && !bgName.isBlank() && !esImagenGlobal(bgName)) {
            escribeSiCambia(new File(dir, FONDO), png(bgBytes));
        } else {
            Files.deleteIfExists(new File(dir, FONDO).toPath());   // volvio al fondo de siempre (o sin fondo)
        }
        escribeAtomico(songini, iniTxt.getBytes(StandardCharsets.UTF_8));
    }

    /** Escribe el Song.ini de un proyecto nuevo (al crearlo: aun sin marcas). */
    public static void escribeSongIni(File songini, String iniTxt) throws IOException {
        escribeAtomico(songini, iniTxt.getBytes(StandardCharsets.UTF_8));
    }

    private static void escribeSiCambia(File f, byte[] data) throws IOException {
        if (f.isFile() && f.length() == data.length && Arrays.equals(Files.readAllBytes(f.toPath()), data)) {
            return;
        }
        escribeAtomico(f, data);
    }

    /** Escribe primero en kia_temp (fuera de la carpeta del proyecto en Drive) y luego lo coloca en su lugar. */
    private static void escribeAtomico(File f, byte[] data) throws IOException {
        Almacen a = almacen;
        if (a != null && esSongIni(f)) {
            a.guarda(carpetaDe(f), new String(data, StandardCharsets.UTF_8));   // el Song.ini va a la base, no a archivo
            return;
        }
        File tmp = kia.util.Carpetas.tempArchivo("proyecto", ".tmp");
        try {
            Files.write(tmp.toPath(), data);
            kia.util.Carpetas.colocaArchivo(tmp, f);
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    // ------------------------------------------------------------------ conversion

    /**
     * Separa el main.kfn de un proyecto nuevo: deja songini.txt y en la carpeta solo las pistas / imagenes
     * que no estuvieran ya (la copia de main.mp3 que traia el .kfn no se vuelve a escribir). Borra el .kfn.
     *
     * @return el songini.txt
     */
    public static File separa(File kfn) throws IOException {
        File dir = kfn.getAbsoluteFile().getParentFile();
        File songini = new File(dir, SONGINI);
        Kfn.Parsed p = Kfn.parse(Files.readAllBytes(kfn.toPath()));
        Kfn.Entry ie = null;
        for (Kfn.Entry e : p.ents) {
            if (e.name.equalsIgnoreCase("song.ini")) {
                ie = e;
            }
        }
        if (ie == null) {
            throw new IOException("El .kfn no trae Song.ini: " + kfn);
        }
        List<Ini.Sec> secs = Ini.parse(new String(ie.data, StandardCharsets.UTF_8));
        Ini.Sec gen = null;
        for (Ini.Sec s : secs) {
            if (s.name.equals("General")) {
                gen = s;
            }
        }
        String[] sp = Ini.get(gen, "Source").split(",", 3);
        String src = sp.length >= 3 ? sp[2].trim() : "";
        File main = new File(dir, MAIN);
        for (Kfn.Entry e : p.ents) {
            if (e.type == 2 || Studio.endsWith(e.name, Studio.AUDIO_EXT)) {
                File destino = new File(dir, e.name);
                if (destino.isFile()) {
                    continue;
                }
                if (main.isFile() && main.length() == e.data.length) {
                    // es la misma pista principal: se usa main.mp3
                    if (e.name.equals(src) && gen != null) {
                        Ini.set(gen, "Source", "1,I," + MAIN);
                    }
                    continue;
                }
                if (!main.isFile() && e.name.equals(src)) {
                    escribeAtomico(main, e.data);
                    if (gen != null) {
                        Ini.set(gen, "Source", "1,I," + MAIN);
                    }
                    continue;
                }
                escribeAtomico(destino, e.data);
            }
        }
        fondoDelKfn(secs, p, dir);
        escribeAtomico(songini, Ini.serialize(secs).getBytes(StandardCharsets.UTF_8));
        Files.delete(kfn.toPath());
        return songini;
    }

    /** Fondo del .kfn: si es uno de kia/source se deja la referencia; si es propio queda como fondo.png. */
    private static void fondoDelKfn(List<Ini.Sec> secs, Kfn.Parsed p, File dir) throws IOException {
        Ini.Sec bg = Ini.findBg(secs);
        String lib = bg != null ? Ini.get(bg, "LibImage") : "";
        if (!lib.isBlank() && !esImagenGlobal(lib)) {
            for (Kfn.Entry e : p.ents) {
                if ((e.type == 3 || Studio.endsWith(e.name, Studio.IMG_EXT)) && clave(e.name).equals(clave(lib))) {
                    escribeAtomico(new File(dir, FONDO), png(e.data));
                    Ini.set(bg, "LibImage", FONDO);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ importar proyectos anteriores

    /** true si por el nombre es una pista sin la voz principal (no sirve como main.mp3). */
    public static boolean esPistaSinVoz(String nombre) {
        String k = clave(nombre).replace('_', ' ');
        return k.contains("sin voz") || k.contains("no vocals") || k.contains("novocals") || k.contains("instrumental")
                || k.contains("coros") || k.startsWith("rv ") || k.contains("karaoke");
    }

    /**
     * Elige la pista principal (la que sera main.mp3) entre los nombres de audio de un proyecto anterior:
     * 1) la que dice "CON VOZ"; 2) "artista - nombre.mp3"; 3) la de Source del Song.ini; 4) la mas grande.
     * Nunca una "SIN VOZ" / no_vocals / instrumental. Regresa el indice, o -1 si no hay ninguna.
     */
    public static int eligePrincipal(List<String> nombres, List<Long> tamanos, String artista, String nombre, String source) {
        for (int i = 0; i < nombres.size(); i++) {
            if (clave(nombres.get(i)).contains("con voz")) {
                return i;
            }
        }
        String esperado = clave(artista + " - " + nombre);
        int porSource = -1, mayor = -1;
        for (int i = 0; i < nombres.size(); i++) {
            String n = nombres.get(i);
            if (esPistaSinVoz(n)) {
                continue;
            }
            if (clave(n.replaceFirst("\\.[^.]+$", "")).equals(esperado)) {
                return i;
            }
            if (source != null && n.equals(source)) {
                porSource = i;
            }
            if (mayor < 0 || tamanos.get(i) > tamanos.get(mayor)) {
                mayor = i;
            }
        }
        return porSource >= 0 ? porSource : mayor;
    }

    /**
     * Crea el proyecto nuevo (songini.txt + main.mp3 [+ fondo.png]) en 'dir' a partir del .kfn de un proyecto
     * anterior. La pista principal sale del .kfn (ver eligePrincipal); si el .kfn no la trae, de los mp3 sueltos
     * de su carpeta. Las pistas sin voz (SIN VOZ, no_vocals) no se copian: las vuelve a generar Remover voz.
     *
     * @return el nombre de la pista que quedo como main.mp3
     */
    public static String importaKfn(File kfn, File dir, String artista, String nombre) throws IOException {
        Kfn.Parsed p = Kfn.parse(Files.readAllBytes(kfn.toPath()));
        Kfn.Entry ie = null;
        List<Kfn.Entry> audios = new ArrayList<>();
        for (Kfn.Entry e : p.ents) {
            if (e.name.equalsIgnoreCase("song.ini")) {
                ie = e;
            } else if (e.type == 2 || Studio.endsWith(e.name, Studio.AUDIO_EXT)) {
                audios.add(e);
            }
        }
        if (ie == null) {
            throw new IOException("El .kfn no trae Song.ini: " + kfn.getName());
        }
        List<Ini.Sec> secs = Ini.parse(new String(ie.data, StandardCharsets.UTF_8));
        Ini.Sec gen = null;
        for (Ini.Sec s : secs) {
            if (s.name.equals("General")) {
                gen = s;
            }
        }
        String[] sp = Ini.get(gen, "Source").split(",", 3);
        String src = sp.length >= 3 ? sp[2].trim() : "";

        List<String> nombres = new ArrayList<>();
        List<Long> tamanos = new ArrayList<>();
        for (Kfn.Entry e : audios) {
            nombres.add(e.name);
            tamanos.add((long) e.data.length);
        }
        int i = eligePrincipal(nombres, tamanos, artista, nombre, src);
        String elegido;
        dir.mkdirs();
        File main = new File(dir, MAIN);
        if (i >= 0) {
            elegido = audios.get(i).name;
            escribeAtomico(main, audios.get(i).data);
        } else {
            // el .kfn no trae la pista con voz: se busca entre los mp3 sueltos de su carpeta
            File[] sueltos = kfn.getAbsoluteFile().getParentFile().listFiles(f -> f.isFile() && Studio.endsWith(f.getName(), Studio.AUDIO_EXT));
            List<File> fs = sueltos == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(sueltos));
            nombres.clear();
            tamanos.clear();
            for (File f : fs) {
                nombres.add(f.getName());
                tamanos.add(f.length());
            }
            int j = eligePrincipal(nombres, tamanos, artista, nombre, src);
            if (j < 0) {
                throw new IOException("No se encontró la pista con voz (CON VOZ / " + artista + " - " + nombre + ".mp3) en " + kfn.getName());
            }
            elegido = fs.get(j).getName();
            Files.copy(fs.get(j).toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (gen != null) {
            Ini.set(gen, "Source", "1,I," + MAIN);
        }
        fondoDelKfn(secs, p, dir);
        escribeAtomico(new File(dir, SONGINI), Ini.serialize(secs).getBytes(StandardCharsets.UTF_8));
        return elegido;
    }

    /** Arma un .kfn completo a partir del proyecto (para quien todavia necesite el archivo: Drive, KaraFun). */
    public static void armaKfn(File songini, File destino) throws IOException {
        Kfn.Parsed p = leer(songini);
        escribeAtomico(destino, Kfn.build(p.header, p.ents));
    }

    // ------------------------------------------------------------------ cambios directos al proyecto

    /** Usa esa pista de audio de la carpeta del proyecto como la que suena (Source del Song.ini). */
    public static void cambiaPista(File songini, File audio) throws IOException {
        File dir = songini.getAbsoluteFile().getParentFile();
        if (!audio.getAbsoluteFile().getParentFile().equals(dir)) {
            Files.copy(audio.toPath(), new File(dir, audio.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        List<Ini.Sec> secs = Ini.parse(texto(songini));
        for (Ini.Sec s : secs) {
            if (s.name.equals("General")) {
                Ini.set(s, "Source", "1,I," + audio.getName());
            }
        }
        escribeAtomico(songini, Ini.serialize(secs).getBytes(StandardCharsets.UTF_8));
    }

    /** Pone esa imagen como fondo del karaoke (se copia a la carpeta si no es un fondo de la app). */
    public static void cambiaFondo(File songini, File imagen) throws IOException {
        File dir = songini.getAbsoluteFile().getParentFile();
        String lib = imagen.getName();
        if (!esImagenGlobal(lib)) {
            escribeAtomico(new File(dir, FONDO), png(Files.readAllBytes(imagen.toPath())));
            lib = FONDO;
        } else {
            Files.deleteIfExists(new File(dir, FONDO).toPath());   // vuelve al fondo de kia/source
        }
        List<Ini.Sec> secs = Ini.parse(texto(songini));
        Ini.Sec bg = Ini.findBg(secs);
        if (bg == null) {
            throw new IOException("El Song.ini no tiene sección de fondo (LibImage).");
        }
        Ini.set(bg, "LibImage", lib);
        escribeAtomico(songini, Ini.serialize(secs).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ imagenes

    /** La imagen en PNG (para fondo.png); si ya es PNG se deja tal cual. */
    static byte[] png(byte[] data) throws IOException {
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return data;
        }
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (img == null) {
            throw new IOException("Formato de imagen no soportado para el fondo.");
        }
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    /** Fondos de la app que antes se metian en cada .kfn. */
    static List<File> imagenesGlobales() {
        List<File> l = new ArrayList<>();
        for (String r : new String[]{kia.form.PropertiesForm.FILE_IMG_CON_FONDO_IA, kia.form.PropertiesForm.FILE_IMG_SIN_FONDO_IA}) {
            if (r != null) {
                File f = new File(r);
                if (f.isFile()) {
                    l.add(f);
                }
            }
        }
        return l;
    }

    static boolean esImagenGlobal(String nombre) {
        for (File g : imagenesGlobales()) {
            if (clave(g.getName()).equals(clave(nombre))) {
                return true;
            }
        }
        return false;
    }

    /** Archivo de la carpeta con ese nombre (sin distinguir acentos ni mayusculas); null si no hay. */
    private static File buscaEn(File dir, String nombre) {
        File exacto = new File(dir, nombre);
        if (exacto.isFile()) {
            return exacto;
        }
        File[] fs = dir.listFiles(File::isFile);
        if (fs != null) {
            for (File f : fs) {
                if (clave(f.getName()).equals(clave(nombre))) {
                    return f;
                }
            }
        }
        return null;
    }

    private static String clave(String n) {
        return Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }
}
