package kia.claude;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ====================================================================================================
//  SONG.INI
// ====================================================================================================
final class Ini {
    static final class Sec {
        String name; List<String[]> kv = new ArrayList<>();
        Sec(String n) { name = n; }
        Sec copy() { Sec s = new Sec(name); for (String[] e : kv) s.kv.add(e.clone()); return s; }
        boolean has(String k) { for (String[] e : kv) if (e[0].equals(k)) return true; return false; }
    }
    static final Pattern SEC = Pattern.compile("^\\[(.+)\\]\\s*$");
    static List<Sec> parse(String text) {
        List<Sec> secs = new ArrayList<>(); Sec cur = new Sec(""); secs.add(cur);
        for (String l : text.split("\\r?\\n|\\r")) {
            Matcher m = SEC.matcher(l);
            if (m.matches()) { cur = new Sec(m.group(1)); secs.add(cur); continue; }
            int i = l.indexOf('=');
            if (i > 0) cur.kv.add(new String[]{l.substring(0, i), l.substring(i + 1)});
        }
        secs.removeIf(s -> s.name.isEmpty() && s.kv.isEmpty());
        return secs;
    }
    static String serialize(List<Sec> secs) {
        StringBuilder sb = new StringBuilder(); boolean first = true;
        for (Sec s : secs) {
            if (!first) sb.append("\n\n"); first = false;
            sb.append('[').append(s.name).append("]\n");
            for (int i = 0; i < s.kv.size(); i++) { if (i > 0) sb.append('\n'); sb.append(s.kv.get(i)[0]).append('=').append(s.kv.get(i)[1]); }
        }
        return sb.append('\n').toString();
    }
    private static Sec mk(String name, String... kv) { Sec s = new Sec(name); for (int i = 0; i < kv.length; i += 2) s.kv.add(new String[]{kv[i], kv[i + 1]}); return s; }
    static List<Sec> defaultIni() {
        List<Sec> l = new ArrayList<>();
        l.add(mk("General", "Title", "", "Artist", "", "Album", "", "Composer", "", "Year", "", "Track", "", "GenreID", "-1", "Copyright", "", "Comment", "",
            "Source", "1,I,", "EffectCount", "2", "LanguageID", "", "DiffMen", "0", "DiffWomen", "0", "KFNType", "0", "Properties", "24", "KaraokeVersion", "",
            "VocalGuide", "", "KaraFunization", "", "InfoScreenBmp", "", "GlobalShift", "0"));
        Sec marks = new Sec("Marks"); for (int i = 0; i < 9; i++) marks.kv.add(new String[]{"Mark" + i, "-1"}); l.add(marks);
        l.add(mk("MP3Music", "NumTracks", "0"));
        l.add(mk("Eff1", "ID", "51", "InPractice", "0", "Enabled", "-1", "Locked", "0", "Color", "#000000", "LibImage", "", "ImageColor", "#FFFFFFFF",
            "AlphaBlending", "Opacity", "OffsetX", "0", "OffsetY", "0", "Depth", "0", "NbAnim", "0"));
        l.add(mk("Eff2", "ID", "2", "InPractice", "1", "Enabled", "-1", "Locked", "0", "Font", "Arial Black*50", "ActiveColor", "#FF0000FF",
            "InactiveColor", "#FFFFFFFF", "FrameColor", "#000000FF", "InactiveFrameColor", "#000000FF", "FrameType", "Frame2", "IsFill", "1", "Preview", "1",
            "Fixed", "0", "LineCount", "4", "OffsetX", "0", "OffsetY", "0", "NbAnim", "0", "TextCount", "0", "InSync", "1"));
        return l;
    }
    static String get(Sec s, String k, String d) { if (s == null) return d; for (String[] e : s.kv) if (e[0].equals(k)) return e[1]; return d; }
    static String get(Sec s, String k) { return get(s, k, ""); }
    static void set(Sec s, String k, Object v) {
        for (String[] e : s.kv) if (e[0].equals(k)) { e[1] = String.valueOf(v); return; }
        s.kv.add(new String[]{k, String.valueOf(v)});
    }
    static Sec findEff(List<Sec> secs) { for (Sec s : secs) if (s.has("TextCount")) return s; return null; }
    static Sec findBg(List<Sec> secs) { for (Sec s : secs) if (s.has("LibImage")) return s; return null; }
    static List<String> chunkSync(List<Long> cs) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); int ln = 0, cnt = 0;
        for (long v : cs) {
            String s = Long.toString(v);
            if (cnt > 0 && ln + s.length() + 1 > 200) { out.add(cur.toString()); cur.setLength(0); ln = 0; cnt = 0; }
            if (cnt > 0) cur.append(','); cur.append(s); ln += s.length() + 1; cnt++;
        }
        if (cnt > 0) out.add(cur.toString());
        return out;
    }
}
