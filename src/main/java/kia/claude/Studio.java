package kia.claude;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.text.PlainDocument;
import kia.claude.acomodo.implementacion.CriterioPalabrasEnlace;
import kia.claude.acomodo.implementacion.CriterioPausa;
import kia.claude.acomodo.implementacion.MedidorPorCaracteres;
import kia.claude.acomodo.modelo.Frase;
import kia.claude.acomodo.modelo.PalabraCantada;
import kia.claude.acomodo.servicio.AcomodadorLetra;

// ====================================================================================================
//  MODELO DEL ESTUDIO (marcas, grabacion, deshacer, .kfn)  -  sin componentes graficos
// ====================================================================================================
final class Studio {
    static final String[] AUDIO_EXT = {".mp3", ".wav", ".ogg", ".m4a", ".flac", ".aac"}, IMG_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp"};
    static boolean endsWith(String n, String[] exts) { n = n.toLowerCase(); for (String e : exts) if (n.endsWith(e)) return true; return false; }
    static String normU(String t) { return t.toLowerCase().replaceAll("[\\W_]+", ""); }
    static final class Extra { final String name; final byte[] data; Extra(String n, byte[] d) { name = n; data = d; } }
    /** Todas las pistas de audio del proyecto (el .kfn puede traer varias: original, instrumental, segunda voz...). audioName es la que suena. */
    final List<Extra> audios = new ArrayList<>();

    /** Estado de UNA pista de texto (la activa vive en los campos de Studio; stash()/loadTrack() los copian). */
    static final class Tk {
        final Props q = new Props(); String text = ""; List<Page> pages = new ArrayList<>(); List<Line> lines = new ArrayList<>(); List<Unit> units = new ArrayList<>(); List<Block> blocks = new ArrayList<>(); List<double[]> bars = new ArrayList<>();
        int sel = -1, nextIdx = 0, fflags = 0; ArrayList<double[]> undoS = new ArrayList<>(), redoS = new ArrayList<>(); List<Ev> events = new ArrayList<>(); Ini.Sec sec;
        PlainDocument doc; TextUndo tu; final Map<Integer, List<Block>> variants = new HashMap<>();
        /** Bloques para otro numero de lineas (evento «Número de líneas»). */
        List<Block> blocksFor(int n) { if (n == q.lines) return blocks; return variants.computeIfAbsent(n, k -> { List<Block> bl = new ArrayList<>(); Lyr.buildBlocks(pages, k, q.preview != 0, bl, new ArrayList<>()); return bl; }); }
    }
    static final String[] TRACK_PAL = {"#FFFFFF", "#FF80FF", "#FFFF80", "#0080FF", "#80FF80", "#FFB060"};
    final List<Tk> tracks = new ArrayList<>(); int cur = 0; Tk lastDel; int lastDelIdx = -1; int evSel = -1;
    Studio() { tracks.add(new Tk()); loadPrefs(); }
    Tk tk() { return tracks.get(cur); }
    final AudioEngine engine = new AudioEngine(); final Props props = new Props();
    String title = "", artist = ""; int react = 100;
    byte[] audioBytes; String audioName = ""; double dur = 0; float[][] peaks;
    /** Onda de la voz aislada (rv_vocals.mp3 / rv_sv_vocals.mp3 de la carpeta del proyecto), normalizada; solo para guiar la digitacion. */
    volatile float[][] vocalPeaks; volatile String vocalName = ""; volatile int vocalTok; boolean showVocals = true;
    static final int VOCAL_BLOCK = 512;
    /** min / max por bloques de la mezcla mono, escalado para que el pico mas alto valga 1. */
    static float[][] vocalPeaksOf(float[] data) {
        int n = data.length / 2 / VOCAL_BLOCK; float[][] r = {new float[n], new float[n]}; float top = 1e-6f;
        for (int i = 0; i < n; i++) {
            float mn = 1e9f, mx = -1e9f;
            for (int j = i * VOCAL_BLOCK; j < (i + 1) * VOCAL_BLOCK; j++) { float m = (data[2 * j] + data[2 * j + 1]) * .5f; if (m < mn) mn = m; if (m > mx) mx = m; }
            r[0][i] = mn; r[1][i] = mx; top = Math.max(top, Math.max(-mn, mx));
        }
        for (int i = 0; i < n; i++) { r[0][i] /= top; r[1][i] /= top; }
        return r;
    }
    /** true si en los bloques [a, b) de la voz guia canta el artista (no es silencio ni el residuo de la separacion). */
    static boolean hayVoz(float[][] vp, int a, int b) {
        for (int i = a; i < b; i++) if (vp[1][i] - vp[0][i] >= .08f) return true;
        return false;
    }
    String bgName = ""; byte[] bgBytes; BufferedImage bgImg; List<Extra> extras = new ArrayList<>(); List<Kfn.Tag> header; List<Ini.Sec> ini = Ini.defaultIni();
    List<Page> pages = new ArrayList<>(); List<Line> lines = new ArrayList<>(); List<Unit> units = new ArrayList<>(); List<Block> blocks = new ArrayList<>(); List<double[]> bars = new ArrayList<>();
    String text = ""; int sel = -1, nextIdx = 0, ver = 0, tver = 0; boolean rec = false, saved = true, free = false, tdUndo = false;
    ArrayList<double[]> undoS = new ArrayList<>(), redoS = new ArrayList<>(); double win = 15.0, viewStart = 0.0;
    Unit repU; List<Unit> repSrc; double rate = 1.0; int rateTok = 0; float[] stretchBuf; double stretchRate = 1.0;
    boolean scrubbing = false; double scrubPos = 0; private long lastNudge = 0;

    final List<Runnable> listeners = new ArrayList<>(); Consumer<String> toastL = s -> { }; Consumer<Boolean> repeatL = b -> { };
    void emit() { for (Runnable r : new ArrayList<>(listeners)) r.run(); }
    void toast(String s) { toastL.accept(s); }

    // ---------- reloj ----------
    double pos() { return scrubbing ? scrubPos : engine.pos(); }

    // ---------- letra ----------
    void setText(String t) {
        List<Unit> old = units; text = t; Lyr.Parsed p = Lyr.parse(t); Lyr.mergeTimes(old, p.units);
        pages = p.pages; lines = p.lines; units = p.units; if (sel >= units.size()) sel = -1;
        nextIdx = Math.min(nextIdx, units.size()); undoS.clear(); redoS.clear(); tver++; marksChanged();
    }

    // ---------- marcas ----------
    double[] snapshot() { double[] s = new double[units.size()]; for (int i = 0; i < s.length; i++) s[i] = units.get(i).t; return s; }
    void pushUndo() { undoS.add(snapshot()); redoS.clear(); if (undoS.size() > 300) undoS.remove(0); }
    private void apply(double[] s) { for (int i = 0; i < s.length; i++) units.get(i).t = s[i]; }
    void undo() {
        while (!undoS.isEmpty()) {
            double[] s = undoS.remove(undoS.size() - 1); if (s.length != units.size()) continue;
            redoS.add(snapshot()); apply(s); marksChanged(); if (rec) nextIdx = idxAfter(pos()); toast("Deshacer"); return;
        }
    }
    void redo() {
        while (!redoS.isEmpty()) {
            double[] s = redoS.remove(redoS.size() - 1); if (s.length != units.size()) continue;
            undoS.add(snapshot()); apply(s); marksChanged(); if (rec) nextIdx = idxAfter(pos()); toast("Rehacer"); return;
        }
    }
    int idxAfter(double T) { int k = -1; for (int i = 0; i < units.size(); i++) { Unit u = units.get(i); if (u.on() && u.t <= T) k = i; } return k + 1; }
    List<Unit> markedList() { List<Unit> r = new ArrayList<>(); for (Unit u : units) if (u.on()) r.add(u); return r; }
    void marksChanged() { Lyr.computeEnds(units); Lyr.buildBlocks(pages, props.lines, props.preview != 0, blocks, bars); tk().variants.clear(); stash(); ver++; saved = false; emit(); }

    // ---------- pistas ----------
    void stash() { Tk t = tk(); t.q.copyStyle(props); t.text = text; t.pages = pages; t.lines = lines; t.units = units; t.blocks = blocks; t.bars = bars; t.sel = sel; t.nextIdx = nextIdx; t.undoS = undoS; t.redoS = redoS; }
    void loadTrack(int i) {
        cur = i; Tk t = tk(); props.copyStyle(t.q); text = t.text; pages = t.pages; lines = t.lines; units = t.units; blocks = t.blocks; bars = t.bars; sel = t.sel; nextIdx = t.nextIdx; undoS = t.undoS; redoS = t.redoS; evSel = -1; tver++;
    }
    void selectTrack(int i) {
        if (i == cur || i < 0 || i >= tracks.size()) return; if (rec) stopRec(false); hideRepeat(); stash(); loadTrack(i); ver++; toast("Pista " + (i + 1) + ": " + trackName(i)); emit();
    }
    String trackName(int i) {
        Tk t = tracks.get(i); String txt = i == cur ? text : t.text, first = ""; for (String l : txt.split("\n")) if (!l.isBlank()) { first = l.replace("/", "").strip(); break; }
        Matcher m = Pattern.compile("^[\\[(](.{1,18}?)[\\])]$").matcher(first); if (m.matches()) return m.group(1);
        return first.isEmpty() ? "Pista " + (i + 1) : first.substring(0, Math.min(14, first.length())) + (first.length() > 14 ? "…" : "");
    }
    void addTrack() {
        if (rec) stopRec(false); stash(); Tk base = tk(), n = new Tk(); n.q.copyStyle(base.q); n.q.inactive = TRACK_PAL[tracks.size() % TRACK_PAL.length]; n.q.oy = base.q.oy - 120; n.q.lines = Math.min(base.q.lines, 2); n.fflags = base.fflags;
        tracks.add(n); loadTrack(tracks.size() - 1); marksChanged(); toast("Pista " + tracks.size() + " agregada: escribe su letra y digítala como siempre");
    }
    boolean deleteTrack() {
        if (tracks.size() < 2) { toast("El karaoke necesita al menos una pista de texto"); return false; }
        if (rec) stopRec(false); stash(); int i = cur; lastDel = tracks.remove(i); lastDelIdx = i; loadTrack(Math.min(i, tracks.size() - 1)); marksChanged(); toast("Pista quitada · usa «Recuperar pista» si fue un error"); return true;
    }
    void restoreTrack() { if (lastDel == null) return; stash(); tracks.add(Math.min(lastDelIdx, tracks.size()), lastDel); int i = tracks.indexOf(lastDel); lastDel = null; loadTrack(i); marksChanged(); }
    void moveTrack(int d) { int j = cur + d; if (j < 0 || j >= tracks.size()) return; stash(); Collections.swap(tracks, cur, j); cur = j; ver++; saved = false; emit(); }

    // ---------- eventos de la pista activa ----------
    List<Ev> evs() { return tk().events; }
    void evTouch() { ver++; saved = false; emit(); }
    Ev evAdd(double t) { Ev e = new Ev(); e.t = U.r3(U.clamp(t, 0, dur > 0 ? dur : t)); evs().add(e); evs().sort(Comparator.comparingDouble(x -> x.t)); evSel = evs().indexOf(e); evTouch(); return e; }
    void evDel(int i) { if (i < 0 || i >= evs().size()) return; evs().remove(i); evSel = -1; evTouch(); }
    void evSetT(int i, double t) { if (i < 0 || i >= evs().size()) return; Ev e = evs().get(i); e.t = U.r3(U.clamp(t, 0, dur > 0 ? dur : t)); evs().sort(Comparator.comparingDouble(x -> x.t)); evSel = evs().indexOf(e); evTouch(); }
    static final String[][] EV_FX = {{"ChgColActiveColor", "Color activo", "col"}, {"ChgColInactiveColor", "Color inactivo", "col"}, {"ChgColFrameColor", "Color del borde", "col"}, {"ChgColInactiveFrameColor", "Borde inactivo", "col"},
        {"ChgLineCount", "Número de líneas", "LineCount"}, {"ChgOffsetX", "Alineación horizontal", "OffsetX"}, {"ChgOffsetY", "Alineación vertical", "OffsetY"}};
    static String[] fxDef(String type) { for (String[] d : EV_FX) if (d[0].equals(type)) return d; return null; }
    /** Valor de una propiedad justo antes del evento e (para proponer un valor inicial razonable al agregar un efecto). */
    String valueBefore(String type, Ev e) {
        Props q = props; String v = switch (type) { case "ChgColActiveColor" -> q.active; case "ChgColInactiveColor" -> q.inactive; case "ChgColFrameColor" -> q.frame; case "ChgColInactiveFrameColor" -> q.iframe;
            case "ChgLineCount" -> String.valueOf(q.lines); case "ChgOffsetX" -> String.valueOf(q.ox); default -> String.valueOf(q.oy); };
        for (Ev x : evs()) { if (x == e || x.t >= e.t) break; for (Ev.Fx f : x.fx) if (f.type.equals(type)) v = fxDef(type)[2].equals("col") ? f.p.getOrDefault("TargetColor", v).substring(0, 7) : f.first(fxDef(type)[2]); }
        return v;
    }
    void evAddFx(String type) {
        if (evSel < 0 || evSel >= evs().size()) return; String[] d = fxDef(type); if (d == null) return; Ev e = evs().get(evSel); Ev.Fx f = new Ev.Fx(); f.type = type; String cv = valueBefore(type, e);
        if (d[2].equals("col")) { f.p.put("TargetColor", cv.toUpperCase() + "FF"); f.p.put("FadeTime", "0"); } else f.p.put(d[2], cv);
        e.fx.add(f); evTouch();
    }

    // ---------- reproduccion y saltos ----------
    void play() { if (engine.buf == null) return; engine.play(); free = false; emit(); }
    void pause() {
        arrastreCursor = false;
        if (scrubbing) { scrubbing = false; engine.pause(); engine.seek(scrubPos); } else engine.pause();
        emit();
    }
    void togglePlay() { if (engine.playing) pause(); else play(); }
    void seekRaw(double t) {
        t = U.clamp(U.r3(t), 0, dur);
        if (scrubbing) scrubPos = t; else engine.seek(t);
        free = false; emit();
    }
    void userSeek(double t) {
        double before = pos(); t = U.clamp(U.r3(t), 0, dur); seekRaw(t);
        if (rec) { if (t < before) nextIdx = idxAfter(t); tdUndo = false; repasoHasta = -1; touchDelete(); }
    }
    /** Teclas adelante/atras: mientras se mantienen pulsadas el audio SIGUE sonando y solo se mueve el cursor; al soltar salta ahi. */
    void stepSeek(double dt) {
        if (dur <= 0) return;
        if (engine.playing && !scrubbing) { scrubbing = true; scrubPos = engine.pos(); }
        userSeek(pos() + dt);
    }
    /** true mientras se arrastra el cursor con el mouse estando en play / grabando. */
    boolean arrastreCursor = false;
    /**
     * Arrastrar el cursor con el mouse. Detenido: salta de inmediato como siempre. Reproduciendo o grabando: solo se
     * mueve la linea del cursor y el audio sigue sonando donde iba; salta al punto final hasta que se suelta (sueltaCursor).
     */
    void arrastraCursor(double t) {
        if (!engine.playing && !arrastreCursor) { userSeek(t); return; }
        arrastreCursor = true; if (!scrubbing) scrubbing = true;
        scrubPos = U.clamp(U.r3(t), 0, dur); free = false; emit();
    }
    void sueltaCursor() {
        if (!arrastreCursor) return;
        arrastreCursor = false; double t = scrubPos; scrubbing = false;
        if (rec && t < engine.pos()) regresaGrabando(t);   // hacia atras grabando: se conservan las digitaciones
        else userSeek(t);   // ya sin scrubbing: el audio salta aqui y, grabando, se ajusta la palabra pendiente
    }
    /** Grabando: hasta este tiempo se esta repasando lo ya digitado (Espacio no marca y nada se borra). */
    double repasoHasta = -1;
    /**
     * Grabando y regresando el cursor (arrastre, Mayus+Tab o regreso de 1 s al soltar una palabra): las digitaciones
     * que quedan atras NO se borran ni se sobrescriben. La palabra pendiente sigue siendo la misma y Espacio vuelve a
     * marcar hasta pasar la ultima digitacion.
     */
    void regresaGrabando(double t) {
        double ult = -1; for (int j = Math.min(nextIdx, units.size()) - 1; j >= 0; j--) if (units.get(j).on()) { ult = units.get(j).t; break; }
        t = U.clamp(U.r3(t), 0, dur);
        if (ult > t) repasoHasta = ult;
        hideRepeat(); seekRaw(t);
    }
    void endScrub() { if (!scrubbing) return; scrubbing = false; engine.seek(scrubPos); emit(); }
    void ensureVisible(double t) { if (t < viewStart || t > viewStart + win * .95) viewStart = U.clamp(t - win * .3, 0, Math.max(0, dur - win)); }
    void follow() {
        if (free) return; double p = pos();
        if (p < viewStart || p > viewStart + win * .9) viewStart = U.clamp(p - win * .1, 0, Math.max(0, dur - win));
    }

    // ---------- grabacion ----------
    void startRec() {
        if (engine.buf == null) { toast("Primero carga una pista de audio"); return; }
        if (units.isEmpty()) { toast("Primero escribe la letra"); return; }
        tdUndo = false; nextIdx = idxAfter(pos()); repasoHasta = -1; rec = true; play(); emit();
    }
    void stopRec(boolean pause) { if (!rec) return; hideRepeat(); rec = false; if (pause) pause(); emit(); }
    void toggleRec() { if (rec) stopRec(true); else startRec(); }
    /** Grabando: una marca existente solo se borra cuando el cabezal la alcanza sin que la hayas vuelto a marcar. */
    void touchDelete() {
        if (!rec || arrastreCursor) return; double p = pos(); List<Unit> gone = new ArrayList<>();
        for (Unit u : units) if (u.i >= nextIdx && u.on() && u.t <= p) gone.add(u);
        if (gone.isEmpty()) return;
        if (!tdUndo) { undoS.add(snapshot()); redoS.clear(); tdUndo = true; }
        for (Unit u : gone) u.t = Double.NaN; marksChanged();
    }
    void markWord() {
        if (!rec) return;
        if (repasoHasta >= 0) {
            if (pos() < repasoHasta) { toast("Repasando · las digitaciones anteriores se conservan"); return; }
            repasoHasta = -1;
        }
        if (nextIdx >= units.size()) { toast("Todas las palabras estan marcadas · pulsa * para terminar"); return; }
        double t = pos() - react / 1000.0; Unit prev = null;
        for (int j = nextIdx - 1; j >= 0; j--) if (units.get(j).on()) { prev = units.get(j); break; }
        if (prev != null) t = Math.max(t, prev.t + .001);
        t = U.clamp(U.r3(t), 0, dur); pushUndo(); Unit u = units.get(nextIdx); u.t = t; sel = nextIdx; nextIdx++;
        marksChanged(); offerRepeat(u);
    }
    void unmark(int i) {
        if (i < 0 || i >= units.size() || !units.get(i).on()) return;
        pushUndo(); units.get(i).t = Double.NaN; marksChanged();
    }
    void nudgeSel(double dt) {
        if (rec) { toast("Deten la grabacion para mover palabras"); return; }
        Unit u = sel >= 0 && sel < units.size() ? units.get(sel) : null;
        if (u == null || !u.on()) { toast("Selecciona una palabra marcada (Tab o clic)"); return; }
        List<Unit> m = markedList(); int k = m.indexOf(u);
        double lo = k > 0 ? m.get(k - 1).t + .001 : 0, hi = k < m.size() - 1 ? m.get(k + 1).t - .001 : dur;
        double nt = U.clamp(U.r3(u.t + dt), lo, Math.max(lo, hi)); if (nt == u.t) return;
        long now = System.nanoTime(); if (now - lastNudge > 700_000_000L) pushUndo();
        lastNudge = now; u.t = nt; marksChanged(); seekRaw(nt); ensureVisible(nt);
    }
    void backspaceMark() {
        if (dur <= 0) return; Unit u = null;
        if (rec) { for (int j = Math.min(nextIdx, units.size()) - 1; j >= 0; j--) if (units.get(j).on()) { u = units.get(j); break; } }
        else if (sel >= 0 && sel < units.size() && units.get(sel).on()) u = units.get(sel);
        else { double p = pos(); for (int j = units.size() - 1; j >= 0; j--) if (units.get(j).on() && units.get(j).t <= p + 1e-6) { u = units.get(j); break; } }
        if (u == null) { toast("No hay una digitacion anterior"); return; }
        double t = u.t; unmark(u.i); sel = u.i; if (rec) { nextIdx = u.i; tdUndo = false; repasoHasta = -1; }
        seekRaw(t); ensureVisible(t);
    }
    void tabNav(int d) {
        List<Unit> m = markedList(); if (m.isEmpty()) return;
        if (engine.playing || rec) { tabNavEnVivo(d, m); return; }
        Unit target;
        if (sel < 0 || !units.get(sel).on()) {
            double p = pos(); target = null;
            if (d > 0) { for (Unit u : m) if (u.t >= p - .0005) { target = u; break; } if (target == null) target = m.get(m.size() - 1); }
            else { for (int i = m.size() - 1; i >= 0; i--) if (m.get(i).t <= p + .0005) { target = m.get(i); break; } if (target == null) target = m.get(0); }
        } else target = m.get(U.clamp(m.indexOf(units.get(sel)) + d, 0, m.size() - 1));
        sel = target.i; seekRaw(target.t); ensureVisible(target.t);
    }
    /**
     * Tab reproduciendo o grabando: el cursor salta a la siguiente palabra digitada a partir de donde va sonando
     * (Mayus+Tab: a la anterior). Grabando, las palabras saltadas se conservan y la siguiente marca con Espacio
     * es la que sigue a la palabra destino.
     */
    void tabNavEnVivo(int d, List<Unit> m) {
        double p = pos(); Unit target = null;
        if (d > 0) { for (Unit u : m) if (u.t > p + .02) { target = u; break; } }
        else { for (int i = m.size() - 1; i >= 0; i--) if (m.get(i).t < p - .35) { target = m.get(i); break; } }
        if (target == null) { toast(d > 0 ? "No hay mas palabras digitadas adelante" : "No hay palabras digitadas antes"); return; }
        sel = target.i;
        if (rec && d < 0) { regresaGrabando(target.t); ensureVisible(target.t); return; }   // atras: no se borra nada
        if (rec) { hideRepeat(); nextIdx = Math.max(nextIdx, Math.min(units.size(), target.i + 1)); tdUndo = false; repasoHasta = -1; }
        seekRaw(target.t); ensureVisible(target.t);
    }
    /** Alt+flechas: salta a la palabra en rojo (Whisper con baja probabilidad) anterior / siguiente, dando la vuelta. Devuelve false si no hay ninguna. */
    boolean reviewNav(int d) {
        List<Unit> rv = new ArrayList<>(); for (Unit u : units) if (u.revisar && u.on()) rv.add(u); if (rv.isEmpty()) return false; if (rec) return true;
        Unit target = null;
        if (sel >= 0 && sel < units.size()) { int s = sel;
            if (d > 0) { for (Unit u : rv) if (u.i > s) { target = u; break; } if (target == null) target = rv.get(0); }
            else { for (int i = rv.size() - 1; i >= 0; i--) if (rv.get(i).i < s) { target = rv.get(i); break; } if (target == null) target = rv.get(rv.size() - 1); }
        } else { double p = pos();
            if (d > 0) { for (Unit u : rv) if (u.t > p + .0005) { target = u; break; } if (target == null) target = rv.get(0); }
            else { for (int i = rv.size() - 1; i >= 0; i--) if (rv.get(i).t < p - .0005) { target = rv.get(i); break; } if (target == null) target = rv.get(rv.size() - 1); }
        }
        sel = target.i; seekRaw(target.t); ensureVisible(target.t); emit();
        toast("Revisar «" + target.txt + "» (" + (rv.indexOf(target) + 1) + "/" + rv.size() + ")" + (Double.isNaN(target.prob) ? "" : " · Whisper " + Math.round(target.prob * 100) + " %"));
        return true;
    }
    void addMarkAt(int i, double t) {
        Unit u = units.get(i); if (u.on()) return; List<Unit> m = markedList(); Unit prev = null, nxt = null;
        for (Unit x : m) { if (x.i < i) prev = x; else if (x.i > i && nxt == null) nxt = x; }
        double lo = prev != null ? prev.t + .001 : 0, hi = nxt != null ? nxt.t - .001 : dur; t = U.clamp(U.r3(t), lo, Math.max(lo, hi));
        pushUndo(); u.t = t; sel = i; marksChanged(); toast("Marcada «" + u.txt + "» en " + U.fmt(t));
    }
    /** Tecla Insert: digita en la posicion del cursor la palabra pendiente de ese tramo (la que sigue a la ultima digitada antes del cursor). */
    void insertMarkAtPos() {
        if (dur <= 0 || units.isEmpty()) return;
        if (rec) { markWord(); return; }
        double T = pos(); Unit prev = null, nxt = null;
        for (Unit x : markedList()) { if (x.t <= T) prev = x; else if (nxt == null) nxt = x; }
        int lo = prev != null ? prev.i + 1 : 0, hi = nxt != null ? nxt.i : units.size();
        if (lo >= hi) { toast("No hay una palabra pendiente en este tramo"); return; }
        addMarkAt(lo, T); ensureVisible(T);
    }

    // ---------- bloques repetidos (coros) ----------
    List<Unit> findRepeatSource(Unit u) {
        Page pg = u.line.page >= 0 && u.line.page < pages.size() ? pages.get(u.line.page) : null; if (pg == null) return null;
        List<Unit> us = pg.units(); if (us.get(0) != u || us.size() < 2) return null;
        List<String> key = new ArrayList<>(); for (Unit x : us) key.add(normU(x.txt)); List<Unit> best = null;
        for (Page q : pages) {
            if (q == pg) continue; List<Unit> qu = q.units(); if (qu.size() != us.size()) continue;
            boolean ok = true; for (int i = 0; i < qu.size() && ok; i++) if (!normU(qu.get(i).txt).equals(key.get(i)) || !qu.get(i).on()) ok = false;
            if (!ok) continue;
            if (best == null || (u.on() && Math.abs(qu.get(0).t - u.t) < Math.abs(best.get(0).t - u.t))) best = qu;
        }
        return best;
    }
    static String blockLabel(List<Unit> us) {
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < Math.min(5, us.size()); i++) { if (i > 0) sb.append(' '); sb.append(us.get(i).txt); }
        return sb + (us.size() > 5 ? "…" : "");
    }
    void applyRepeat(Unit u, double startT, List<Unit> src) {
        List<Unit> us = pages.get(u.line.page).units(); if (src == null || us.size() != src.size()) return;
        Unit before = null; for (Unit x : markedList()) if (!us.contains(x) && x.i < us.get(0).i) before = x;
        double st = Math.max(startT, before != null ? before.t + .001 : 0), off = src.get(0).t; pushUndo();
        for (int i = 0; i < us.size(); i++) us.get(i).t = U.clamp(U.r3(st + (src.get(i).t - off)), 0, dur);
        double last = us.get(us.size() - 1).t; int cut = 0;
        for (Unit x : units) if (x.i > us.get(us.size() - 1).i && x.on() && x.t <= last) { x.t = Double.NaN; cut++; }
        sel = us.get(0).i; if (rec) nextIdx = us.get(us.size() - 1).i + 1; tdUndo = false; marksChanged(); hideRepeat();
        toast("Bloque repetido aplicado: " + us.size() + " palabras con la misma separacion" + (cut > 0 ? " · se quitaron " + cut + " marcas que quedaban dentro" : ""));
    }
    void offerRepeat(Unit u) { List<Unit> src = findRepeatSource(u); if (src == null) { hideRepeat(); return; } repU = u; repSrc = src; repeatL.accept(true); }
    void hideRepeat() { if (repU != null) { repU = null; repSrc = null; repeatL.accept(false); } }
    void acceptRepeat() { if (repU != null) applyRepeat(repU, repU.t, repSrc); }

    // ---------- Whisper ----------
    java.util.function.Consumer<String> lyricsSetter = t -> { };
    /**
     * Digita la pista activa con los tiempos de un JSON de Whisper.
     *
     * @param replace true: la letra se reemplaza por las palabras del JSON, acomodada para no pasar la guia de ancho (ver
     *                {@link AcomodadorLetra}); false: solo se digita la letra actual. Si la pista no tiene letra siempre se toma la del JSON.
     */
    String whisperImport(Object json, String fname, boolean replace) {
        if (rec) stopRec(false); Whisper.Parsed P = Whisper.parse(json); boolean created = false; List<Whisper.W> ws = P.words; int omit = 0;
        if (replace || units.isEmpty()) {
            List<Frase> frases = new ArrayList<>(); List<Whisper.W> usadas = new ArrayList<>(); boolean todas = true;
            for (Whisper.Seg sg : P.segs) {
                List<PalabraCantada> ps = new ArrayList<>();
                if (sg.words.isEmpty()) { todas = false; if (dur > 0 && sg.s >= dur) continue; for (String x : sg.text.strip().split("\\s+")) if (!x.isEmpty()) ps.add(new PalabraCantada(x, Double.NaN, Double.NaN)); }
                else {
                    if (Whisper.colapsado(sg)) { omit++; continue; }
                    for (Whisper.W w : sg.words) { if (dur > 0 && w.s >= dur - .01) continue; String x = w.w.replace("/", "").strip(); if (x.isEmpty()) continue; ps.add(new PalabraCantada(x, w.s, w.e)); usadas.add(w); }
                }
                if (!ps.isEmpty()) frases.add(new Frase(ps));
            }
            if (frases.isEmpty()) throw new IllegalArgumentException("El JSON no trae texto para la letra");
            AcomodadorLetra acomodador = new AcomodadorLetra(new MedidorPorCaracteres(U.caracteresPorLinea(props)), List.of(CriterioPausa.segunRitmo(frases), new CriterioPalabrasEnlace()));
            lyricsSetter.accept(acomodador.acomodar(frases)); created = true;
            if (todas) ws = usadas;                                                     // sin los segmentos alucinados: alinea palabra por palabra
        }
        Whisper.Result R = Whisper.align(units, ws); pushUndo(); double prev = -1; int mk = 0, rev = 0; Unit first = null;
        Double minProb = kia.form.PropertiesForm.getWhisperProbabilidad();            // WHISPER_PROBABILIDAD de comand.properties; vacia = no se marca nada
        for (Unit u : units) { Double t = R.T.get(u), p = R.P.get(u);
            if (t != null) { if (dur > 0 && t > dur - .01) t = null; else { t = Math.max(t, prev + .005); prev = t; mk++; } }
            u.t = t == null ? Double.NaN : U.r3(t); u.prob = p == null ? Double.NaN : p; u.revisar = t != null && minProb != null && p != null && p < minProb; if (u.revisar) rev++;
            if (first == null && u.on()) first = u; }
        sel = -1; marksChanged(); if (first != null && dur > 0) { seekRaw(first.t); ensureVisible(first.t); }   // la linea de tiempo se coloca en la primera palabra
        int pct = R.n > 0 ? (int) Math.round(100.0 * R.hit / R.n) : 0, miss = units.size() - mk;
        return (created ? "Letra y tiempos importados" : "Tiempos importados") + " de " + (fname == null ? "Whisper" : fname) + ": " + mk + "/" + units.size() + " marcadas · " + pct + "% de palabras coinciden"
            + (R.interp > 0 ? " · " + R.interp + " interpoladas" : "") + (miss > 0 ? " · " + miss + " sin marcar" : "") + (omit > 0 ? " · " + omit + " segmentos repetidos omitidos" : "")
            + (minProb == null ? " · WHISPER_PROBABILIDAD vacía: no se marcan palabras a revisar" : " · " + rev + " en rojo para revisar (prob. < " + Math.round(minProb * 100) + "%)") + (P.approx ? " · (el JSON solo tenía tiempos por segmento: aproximado)" : "") + (pct < 40 ? " · ¿es la letra y el track correctos?" : "") + " · Ctrl+Z deshace";
    }

    // ---------- volumen ----------
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(KaraokeStudio.class);
    double volume() { return engine.vol; }
    void setVolume(double v) { engine.vol = (float) U.clamp(v, 0, 1.5); if (engine.vol > 0) engine.muted = false; try { PREFS.putDouble("vol", engine.vol); } catch (Exception ignored) { } emit(); }
    void toggleMute() { engine.muted = !engine.muted; emit(); }
    void loadPrefs() { try { engine.vol = (float) U.clamp(PREFS.getDouble("vol", 1.0), 0, 1.5); } catch (Exception ignored) { } }

    // ---------- audio / fondo ----------
    void loadAudioBytes(byte[] data, String name) throws Exception {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp3"; float[] arr = Audio.decode(data, ext);
        engine.load(arr); audioBytes = data; audioName = name; dur = engine.dur;
        int ai = -1; for (int i = 0; i < audios.size(); i++) if (audios.get(i).name.equals(name)) ai = i;
        if (ai >= 0) audios.set(ai, new Extra(name, data)); else audios.add(new Extra(name, data)); peaks = engine.peaks(512); stretchBuf = null; rate = 1.0; rateTok++;
        viewStart = 0.0; scrubbing = false; saved = false; if (title.isEmpty()) title = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        marksChanged();
    }
    /** Cambia la pista que se escucha (y la que queda como Source al guardar); conserva la posicion y la velocidad. */
    void selectAudio(String name) throws Exception {
        if (name == null || name.equals(audioName)) return;
        Extra a = null; for (Extra e : audios) if (e.name.equals(name)) a = e;
        if (a == null) throw new IOException("El proyecto no tiene la pista " + name);
        double p = pos(), r = rate, vs = viewStart; stopRec(false); pause();
        loadAudioBytes(a.data, a.name); viewStart = Math.min(vs, Math.max(0, dur - win)); engine.seek(Math.min(p, dur)); if (r != 1.0) setRate(r);
        toast("Pista de audio: " + name); emit();
    }
    /**
     * Agrega (o reemplaza, si ya hay una con el mismo nombre) una pista de audio al proyecto. No cambia la que se
     * escucha, salvo que el proyecto no tuviera audio. Se guarda en el .kfn al guardar.
     */
    void addAudio(File f) throws Exception {
        String name = f.getName();
        if (!endsWith(name, AUDIO_EXT)) throw new IOException("Formato de audio no soportado: " + name + " (usa mp3, wav, ogg, m4a, flac o aac)");
        byte[] data = Files.readAllBytes(f.toPath());
        if (data.length == 0) throw new IOException("El archivo está vacío: " + name);
        String ext = name.substring(name.lastIndexOf('.'));
        if (Audio.decode(data, ext).length == 0) throw new IOException("No se pudo leer el audio de " + name);
        boolean reemplaza = audios.stream().anyMatch(e -> e.name.equals(name));
        audios.removeIf(e -> e.name.equals(name)); audios.add(new Extra(name, data));
        if (audioBytes == null || name.equals(audioName)) {
            double p = audioBytes == null ? 0 : pos(); stopRec(false); pause();
            loadAudioBytes(data, name); engine.seek(Math.min(p, dur));
        }
        saved = false; toast((reemplaza ? "Pista reemplazada: " : "Pista agregada: ") + name); emit();
    }
    /** Quita una pista de audio del proyecto; si era la que se escucha, pasa a la primera que quede. */
    void removeAudio(String name) throws Exception {
        if (audios.size() <= 1) throw new IOException("El proyecto debe conservar al menos una pista de audio.");
        Extra quitar = null; for (Extra e : audios) if (e.name.equals(name)) quitar = e;
        if (quitar == null) throw new IOException("El proyecto no tiene la pista " + name);
        audios.remove(quitar);
        if (name.equals(audioName)) {
            Extra sig = audios.get(0); double p = pos(), r = rate, vs = viewStart; stopRec(false); pause();
            loadAudioBytes(sig.data, sig.name); viewStart = Math.min(vs, Math.max(0, dur - win)); engine.seek(Math.min(p, dur)); if (r != 1.0) setRate(r);
        }
        saved = false; toast("Pista quitada: " + name + " · se aplica al guardar (Ctrl+S)"); emit();
    }
    void loadAudio(File f) throws Exception { loadAudioBytes(Files.readAllBytes(f.toPath()), f.getName()); toast("Audio cargado: " + f.getName() + " (" + U.fmt(dur).substring(0, 5) + ")"); }
    void setBg(byte[] data, String name) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data)); if (img == null) throw new IOException("Formato de imagen no soportado (usa jpg, png, bmp o gif)");
        if (bgBytes != null && !bgName.equals(name) && extras.stream().noneMatch(e -> e.name.equals(bgName))) extras.add(new Extra(bgName, bgBytes));
        extras.removeIf(e -> e.name.equals(name)); bgName = name; bgBytes = data; bgImg = img; saved = false; emit();
    }
    void loadBg(File f) throws IOException { setBg(Files.readAllBytes(f.toPath()), f.getName()); toast("Fondo: " + f.getName()); }
    void noBg() {
        if (bgBytes != null && extras.stream().noneMatch(e -> e.name.equals(bgName))) extras.add(new Extra(bgName, bgBytes));
        bgName = ""; bgBytes = null; bgImg = null; saved = false; emit();
    }
    void selectBg(String name) throws IOException {
        if (name == null || name.isEmpty()) { noBg(); return; }
        for (Extra e : extras) if (e.name.equals(name)) { setBg(e.data, e.name); return; }
    }

    // ---------- velocidad sin cambiar el tono ----------
    void setRate(double r) {
        rateTok++; final int tok = rateTok;
        if (r == 1 || engine.data == null || (stretchBuf != null && stretchRate == r)) {
            engine.applyRate(r, r == 1 ? null : stretchBuf); rate = r; emit(); return;
        }
        toast("Ajustando la velocidad sin cambiar el tono…");
        new Thread(() -> {
            float[] arr = null; try { arr = Audio.stretch(engine.data, r); } catch (Exception ignored) { }
            final float[] res = arr;
            SwingUtilities.invokeLater(() -> {
                if (tok != rateTok) return;
                if (res == null) toast("No se pudo cambiar la velocidad (¿ffmpeg instalado?)");
                else { stretchBuf = res; stretchRate = r; engine.applyRate(r, res); rate = r; toast("Velocidad " + String.valueOf(r).replace('.', ',') + "×"); }
                emit();
            });
        }, "stretch").start();
    }

    // ---------- .kfn ----------
    /** Una pista de texto -> pares clave/valor de su seccion Eff (conserva las claves que no manejamos). */
    private List<String[]> effKv(Tk t) {
        Ini.Sec base = t.sec != null ? t.sec : Ini.findEff(Ini.defaultIni()); List<String[]> kept = new ArrayList<>();
        for (String[] kv : base.kv) if (!kv[0].matches("^(Sync\\d+|Text\\d+|TextCount|InSync|NbAnim|Anim\\d+)$")) kept.add(kv.clone());
        Props q = t.q;
        java.util.function.BiConsumer<String, Object> put = (k, v) -> { for (String[] e : kept) if (e[0].equals(k)) { e[1] = String.valueOf(v); return; } kept.add(new String[]{k, String.valueOf(v)}); };
        put.accept("Font", q.font + "*" + (q.fsize + (q.bold ? 256 : 0) + (q.italic ? 512 : 0) + (t.fflags & ~768))); put.accept("ActiveColor", q.active.toUpperCase() + q.aActive); put.accept("InactiveColor", q.inactive.toUpperCase() + q.aInactive);
        put.accept("FrameColor", q.frame.toUpperCase() + q.aFrame); put.accept("InactiveFrameColor", q.iframe.toUpperCase() + q.aIframe); put.accept("FrameType", q.ftype);
        put.accept("IsFill", q.fill != 0 ? 1 : 0); put.accept("Preview", q.preview != 0 ? 1 : 0); put.accept("Fixed", q.fixed != 0 ? 1 : 0); put.accept("LineCount", q.lines);
        put.accept("OffsetX", q.ox); put.accept("OffsetY", q.oy);
        int iL = kept.size() - 1; for (int i = 0; i < kept.size(); i++) if (kept.get(i)[0].equals("Locked")) { iL = i; break; }
        List<String[]> head = new ArrayList<>(kept.subList(0, iL + 1)), rest = new ArrayList<>(kept.subList(iL + 1, kept.size()));
        long prev = 0; List<Long> cs = new ArrayList<>();
        for (Unit u : t.units) { long v = !u.on() ? prev + 1 : Math.round(u.t * 100); if (v < prev) v = prev; prev = v; cs.add(v); }
        List<String> sync = Ini.chunkSync(cs); List<String> text = new ArrayList<>();
        for (int i = 0; i < t.pages.size(); i++) { if (i > 0) text.add(""); for (Line l : t.pages.get(i).lines) text.add(l.raw); }
        List<String[]> kv = new ArrayList<>(head);
        for (int i = 0; i < sync.size(); i++) kv.add(new String[]{"Sync" + i, sync.get(i)});
        kv.addAll(rest); List<Ev> ev = new ArrayList<>(t.events); ev.sort(Comparator.comparingDouble(x -> x.t));
        kv.add(new String[]{"NbAnim", String.valueOf(ev.size())}); for (int i = 0; i < ev.size(); i++) kv.add(new String[]{"Anim" + i, ev.get(i).str()});
        kv.add(new String[]{"TextCount", String.valueOf(text.size())});
        for (int i = 0; i < text.size(); i++) kv.add(new String[]{"Text" + i, text.get(i)});
        kv.add(new String[]{"InSync", "1"}); return kv;
    }
    String makeIni() {
        stash(); List<Ini.Sec> secs = new ArrayList<>(); for (Ini.Sec s : ini) secs.add(s.copy());
        Ini.Sec gen = null; for (Ini.Sec s : secs) if (s.name.equals("General")) { gen = s; break; }
        Ini.set(gen, "Title", title); Ini.set(gen, "Artist", artist); Ini.set(gen, "Source", "1,I," + audioName);
        Props q = props; Ini.Sec bg = Ini.findBg(secs);
        if (bg != null) {
            Ini.set(bg, "LibImage", bgName); Ini.set(bg, "Color", q.bgColor);
            Ini.set(bg, "ImageColor", q.bgTint.toUpperCase() + String.format("%02X", Math.round(U.clamp(q.bgTintA, 0, 100) * 255 / 100.0)));
            Ini.set(bg, "AlphaBlending", q.bgBlend); Ini.set(bg, "OffsetX", q.bgOx); Ini.set(bg, "OffsetY", q.bgOy); Ini.set(bg, "Depth", q.bgDepth);
        }
        List<Ini.Sec> others = new ArrayList<>(); int before = 0; boolean seen = false;
        for (Ini.Sec x : secs) { if (x.has("TextCount")) seen = true; else { others.add(x); if (!seen) before++; } }
        List<Ini.Sec> tsecs = new ArrayList<>(); for (Tk t : tracks) { Ini.Sec e = new Ini.Sec("Eff"); e.kv = effKv(t); tsecs.add(e); }
        int at = seen ? before : others.size();
        if (!seen) { int li = -1; for (int i = 0; i < others.size(); i++) if (others.get(i).name.matches("^Eff\\d+$")) li = i; at = li >= 0 ? li + 1 : others.size(); }
        List<Ini.Sec> out = new ArrayList<>(others.subList(0, at)); out.addAll(tsecs); out.addAll(others.subList(at, others.size())); int ne = 0;
        for (Ini.Sec x : out) if (x.name.matches("^Eff\\d*$")) x.name = "Eff" + (++ne);
        Ini.set(gen, "EffectCount", ne); return Ini.serialize(out);
    }
    /** Song.ini que se escribio en el ultimo guardado (para subirlo a la base de datos). */
    String ultimoIni;
    void saveKfn(File path) throws Exception {
        if (audioBytes == null) throw new IOException("Falta la pista de audio");
        // proyecto sin .kfn: un fondo propio siempre se guarda como fondo.png (los de kia/source no se copian)
        if (ProyectoSongIni.esSongIni(path) && bgBytes != null && !bgName.isEmpty() && !ProyectoSongIni.esImagenGlobal(bgName)) bgName = ProyectoSongIni.FONDO;
        stash(); boolean any = false; for (Tk t : tracks) if (!t.units.isEmpty()) any = true; if (!any) throw new IOException("Falta la letra");
        List<Kfn.Tag> hd = new ArrayList<>(); for (Kfn.Tag t : (header != null ? header : Kfn.defaultHeader())) hd.add(new Kfn.Tag(t.tag, t.typ, t.val, t.bytes));
        for (Kfn.Tag t : hd) if (t.tag.equals("SORC")) t.bytes = ("1,I," + audioName).getBytes(StandardCharsets.UTF_8);
        List<Kfn.Entry> files = new ArrayList<>(); for (Extra e : extras) files.add(new Kfn.Entry(e.name, 3, e.data));
        if (bgBytes != null) files.add(new Kfn.Entry(bgName, 3, bgBytes));
        for (Extra a : audios) if (!a.name.equals(audioName)) files.add(new Kfn.Entry(a.name, 2, a.data));      // conserva TODAS las pistas de audio
        String iniTxt = makeIni();
        if (ProyectoSongIni.esSongIni(path)) {
            // proyecto sin .kfn: songini.txt + las pistas / imagenes sueltas en la carpeta (solo si cambiaron)
            List<Extra> pistas = new ArrayList<>(audios); pistas.removeIf(a -> a.name.equals(audioName)); pistas.add(new Extra(audioName, audioBytes));
            ProyectoSongIni.guardar(path, iniTxt, pistas, bgName, bgBytes, extras);
            ultimoIni = iniTxt; saved = true; emit(); return;
        }
        files.add(new Kfn.Entry(audioName, 2, audioBytes)); files.add(new Kfn.Entry("Song.ini", 1, iniTxt.getBytes(StandardCharsets.UTF_8)));
        Files.write(path.toPath(), Kfn.build(hd, files)); ultimoIni = iniTxt; saved = true; emit();
    }
    private static int toInt(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    /** Pista a partir de una seccion Eff con TextCount. */
    static Tk trackOf(Ini.Sec eff) {
        Tk T = new Tk(); Props q = T.q; T.sec = eff.copy();
        String f = Ini.get(eff, "Font"); if (f.isEmpty()) f = "Arial Black*50"; int ix = f.indexOf('*'); q.font = ix >= 0 ? f.substring(0, ix) : f;
        int raw = ix >= 0 ? toInt(f.substring(ix + 1), 50) : 50, flags = raw > 255 ? raw - raw % 256 : 0;
        q.fsize = raw % 256 == 0 ? 50 : raw % 256; q.bold = (flags & 256) != 0; q.italic = (flags & 512) != 0; T.fflags = flags;
        String[][] cols = {{"ActiveColor", "#FF0000FF"}, {"InactiveColor", "#FFFFFFFF"}, {"FrameColor", "#000000FF"}, {"InactiveFrameColor", "#000000FF"}};
        for (int k = 0; k < 4; k++) {
            String v = Ini.get(eff, cols[k][0]); if (v.isEmpty()) v = cols[k][1]; String c = v.substring(0, Math.min(7, v.length())), a = v.length() >= 9 ? v.substring(7, 9) : "FF";
            switch (k) { case 0 -> { q.active = c; q.aActive = a; } case 1 -> { q.inactive = c; q.aInactive = a; } case 2 -> { q.frame = c; q.aFrame = a; } default -> { q.iframe = c; q.aIframe = a; } }
        }
        q.fill = toInt(Ini.get(eff, "IsFill", "1"), 1) != 0 ? 1 : 0; q.preview = toInt(Ini.get(eff, "Preview", "1"), 1) != 0 ? 1 : 0; q.fixed = toInt(Ini.get(eff, "Fixed", "0"), 0) != 0 ? 1 : 0;
        q.lines = U.clamp(toInt(Ini.get(eff, "LineCount", "4"), 4), 1, 4); q.ox = toInt(Ini.get(eff, "OffsetX", "0"), 0); q.oy = toInt(Ini.get(eff, "OffsetY", "0"), 0);
        q.ftype = Ini.get(eff, "FrameType").isEmpty() ? "Frame2" : Ini.get(eff, "FrameType");
        int n = toInt(Ini.get(eff, "TextCount", "0"), 0); StringBuilder tx = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) tx.append('\n'); tx.append(Ini.get(eff, "Text" + i)); }
        T.text = tx.toString(); Lyr.Parsed p = Lyr.parse(T.text); T.pages = p.pages; T.lines = p.lines; T.units = p.units; List<Double> times = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String sv = Ini.get(eff, "Sync" + i, null); if (sv == null) break;
            for (String x : sv.split(",")) if (!x.trim().isEmpty()) times.add(Integer.parseInt(x.trim()) / 100.0);
        }
        for (int i = 0; i < T.units.size(); i++) T.units.get(i).t = i < times.size() ? times.get(i) : Double.NaN;
        Lyr.computeEnds(T.units); Lyr.buildBlocks(T.pages, q.lines, q.preview != 0, T.blocks, T.bars);
        for (int i = 0, na = toInt(Ini.get(eff, "NbAnim", "0"), 0); i < na; i++) { Ev e = Ev.parse(Ini.get(eff, "Anim" + i, null)); if (e != null) T.events.add(e); }
        T.events.sort(Comparator.comparingDouble(e -> e.t)); T.tu = null; return T;
    }
    void openKfn(File path) throws Exception {
        // songini.txt (proyecto sin .kfn): se arma en memoria lo mismo que traia el .kfn
        Kfn.Parsed pr = ProyectoSongIni.esSongIni(path) ? ProyectoSongIni.leer(path) : Kfn.parse(Files.readAllBytes(path.toPath())); Kfn.Entry ie = null;
        for (Kfn.Entry e : pr.ents) if (e.name.equalsIgnoreCase("song.ini")) { ie = e; break; }
        if (ie == null) throw new IOException("El .kfn no trae Song.ini");
        List<Ini.Sec> secs = Ini.parse(new String(ie.data, StandardCharsets.UTF_8)); Ini.Sec gen = null; for (Ini.Sec s : secs) if (s.name.equals("General")) { gen = s; break; }
        Ini.Sec eff = Ini.findEff(secs), bgs = Ini.findBg(secs); if (eff == null) throw new IOException("El Song.ini no tiene letra (Eff con TextCount)");
        stopRec(false); pause(); ini = secs; header = pr.header; title = Ini.get(gen, "Title"); artist = Ini.get(gen, "Artist");
        String[] sp = Ini.get(gen, "Source").split(",", 3); String src = sp.length >= 3 ? sp[2].trim() : "";
        Kfn.Entry ae = null; for (Kfn.Entry e : pr.ents) if (e.name.equals(src)) { ae = e; break; }
        if (ae == null) for (Kfn.Entry e : pr.ents) if (endsWith(e.name, AUDIO_EXT)) { ae = e; break; }
        bgName = ""; bgBytes = null; bgImg = null;
        audios.clear(); for (Kfn.Entry e : pr.ents) if (endsWith(e.name, AUDIO_EXT)) audios.add(new Extra(e.name, e.data));
        if (ae != null) loadAudioBytes(ae.data, ae.name); else toast("El .kfn no trae audio");
        String bn = bgs != null ? Ini.get(bgs, "LibImage") : ""; List<Kfn.Entry> imgs = new ArrayList<>(); Kfn.Entry bge = null;
        for (Kfn.Entry e : pr.ents) if (e.type == 3 || endsWith(e.name, IMG_EXT)) { imgs.add(e); if (e.name.equals(bn)) bge = e; }
        extras = new ArrayList<>(); for (Kfn.Entry e : imgs) if (e != bge) extras.add(new Extra(e.name, e.data));
        if (bge != null) { try { setBg(bge.data, bge.name); } catch (IOException ex) { toast("No se pudo leer la imagen de fondo: " + ex.getMessage()); } }
        tracks.clear(); for (Ini.Sec es : secs) if (es.has("TextCount")) tracks.add(trackOf(es)); lastDel = null;
        Props q = props;
        if (bgs != null) {
            String ic = Ini.get(bgs, "ImageColor", "#FFFFFFFF"); String col = Ini.get(bgs, "Color", "#000000");
            q.bgColor = col.isEmpty() ? "#000000" : col.substring(0, Math.min(7, col.length())); q.bgTint = ic.length() >= 7 ? ic.substring(0, 7) : "#FFFFFF";
            q.bgTintA = ic.length() >= 9 ? (int) Math.round(Integer.parseInt(ic.substring(7, 9), 16) * 100 / 255.0) : 100;
            String bl = Ini.get(bgs, "AlphaBlending", "Opacity"); q.bgBlend = bl.isEmpty() ? "Opacity" : bl;
            q.bgOx = toInt(Ini.get(bgs, "OffsetX", "0"), 0); q.bgOy = toInt(Ini.get(bgs, "OffsetY", "0"), 0); q.bgDepth = toInt(Ini.get(bgs, "Depth", "0"), 0);
        }
        loadTrack(0); sel = -1; viewStart = 0.0; marksChanged(); saved = true; if (engine.buf != null) engine.seek(0); tver++; emit();
        int nu = 0; for (Tk t : tracks) nu += t.units.size();
        toast("Abierto: " + path.getName() + (tracks.size() > 1 ? " · " + tracks.size() + " pistas de texto" : "") + (nu == 0 ? " · sin letra" : ""));
    }
}
