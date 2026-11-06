package kia.claude;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Whisper {
    static final class W { String w; double s, e, p = Double.NaN; W(String w, double s, double e) { this.w = w; this.s = s; this.e = e; } }
    static final class Seg { String text; double s, e; final List<W> words = new ArrayList<>(); Seg(String t, double s, double e) { text = t; this.s = s; this.e = e; } }
    static final class Parsed { final List<W> words = new ArrayList<>(); final List<Seg> segs = new ArrayList<>(); boolean approx; }
    static final class Word { final List<Unit> units = new ArrayList<>(); String txt = "", n = ""; }
    static final class Result { List<Word> L; Map<Unit, Double> T = new HashMap<>(), P = new HashMap<>(); int hit, interp, n; }

    static String norm(String x) { return java.text.Normalizer.normalize(x == null ? "" : x.toLowerCase(), java.text.Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "").replaceAll("[^a-z0-9]", ""); }
    static double num(Object v) { if (v instanceof Number n) { double d = n.doubleValue(); return Double.isFinite(d) ? d : Double.NaN; } if (v instanceof String t) { try { return Double.parseDouble(t.trim()); } catch (Exception e) { return Double.NaN; } } return Double.NaN; }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked") static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : null; }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** Solo hay tiempos por segmento: se reparten entre sus palabras (si el segmento es mucho mas largo que lo cantable, las palabras van al final). */
    static List<W> splitEven(String text, double s0, double e0) {
        String[] ws = text.trim().split("\\s+"); List<W> out = new ArrayList<>(); int[] len = new int[ws.length]; int tot = 0; double need = 0;
        for (int i = 0; i < ws.length; i++) { len[i] = Math.max(1, ws[i].replaceAll("[^\\p{L}\\p{N}]", "").length()); tot += len[i]; need += .2 + .07 * len[i]; }
        double a = s0, b = e0; if (b - a > 1.6 * need + .8) a = b - Math.min(b - a, 1.6 * need); double t = a;
        for (int i = 0; i < ws.length; i++) { if (ws[i].isEmpty()) continue; double d = (b - a) * len[i] / Math.max(1, tot); out.add(new W(ws[i], t, t + d)); t += d; }
        return out;
    }
    private static List<W> mapW(List<Object> arr) {
        List<W> out = new ArrayList<>(); if (arr == null) return out;
        for (Object o : arr) { Map<String, Object> m = map(o); if (m == null) continue;
            String w = str(m.get("word") != null ? m.get("word") : m.get("text")).trim(); double s = num(m.get("start")), e = num(m.get("end"));
            List<Object> ts = list(m.get("timestamp")); if (Double.isNaN(s) && ts != null && ts.size() > 0) s = num(ts.get(0)); if (Double.isNaN(e) && ts != null && ts.size() > 1) e = num(ts.get(1));
            if (!w.isEmpty() && !Double.isNaN(s)) { W x = new W(w, s, e); x.p = num(m.get("probability")); out.add(x); } }
        return out;
    }
    static Parsed parse(Object j) {
        Parsed P = new Parsed(); Object root = j;
        if (j instanceof List<?> l) { Map<String, Object> first = l.isEmpty() ? null : map(l.get(0)); Map<String, Object> w = new LinkedHashMap<>();
            if (first != null && (first.containsKey("words") || first.containsKey("segments"))) w.put("segments", l); else w.put("word_segments", l); root = w; }
        Map<String, Object> m = map(root); if (m == null) throw new IllegalArgumentException("No reconozco el formato del JSON");
        if (list(m.get("segments")) != null) {
            for (Object so : list(m.get("segments"))) { Map<String, Object> sg = map(so); if (sg == null) continue; String text = str(sg.get("text")).trim(); double s0 = num(sg.get("start")), e0 = num(sg.get("end"));
                Seg seg = new Seg(text, s0, e0); P.segs.add(seg); List<W> ws = list(sg.get("words")) != null ? mapW(list(sg.get("words"))) : null;
                if (ws != null && !ws.isEmpty()) { P.words.addAll(ws); seg.words.addAll(ws); } else if (!Double.isNaN(s0) && !Double.isNaN(e0) && !text.isEmpty()) { P.approx = true; P.words.addAll(splitEven(text, s0, e0)); } }
        } else if (list(m.get("word_segments")) != null) {
            List<W> ws = mapW(list(m.get("word_segments"))); P.words.addAll(ws); if (!ws.isEmpty()) { StringBuilder sb = new StringBuilder(); for (W w : ws) sb.append(sb.length() > 0 ? " " : "").append(w.w); P.segs.add(new Seg(sb.toString(), ws.get(0).s, ws.get(ws.size() - 1).e)); }
        } else if (list(m.get("transcription")) != null) {                                  // whisper.cpp (ms)
            for (Object so : list(m.get("transcription"))) { Map<String, Object> sg = map(so); if (sg == null) continue; Map<String, Object> o = map(sg.get("offsets")); double s0 = o != null ? num(o.get("from")) / 1000 : Double.NaN, e0 = o != null ? num(o.get("to")) / 1000 : Double.NaN;
                String text = str(sg.get("text")).trim(); List<W> ws = null; List<Object> tk = list(sg.get("tokens"));
                if (tk != null) { boolean anyOff = false; for (Object t : tk) if (map(t) != null && map(t).get("offsets") != null) anyOff = true;
                    if (anyOff) { ws = new ArrayList<>(); W cur = null;
                        for (Object t : tk) { Map<String, Object> tm = map(t); if (tm == null) continue; String tx = str(tm.get("text")); String tt = tx.trim(); if (tt.isEmpty() || tt.matches("^\\[.*\\]$") || tt.matches("^<\\|.*\\|>$")) continue;
                            Map<String, Object> to = map(tm.get("offsets")); double f = to != null && !Double.isNaN(num(to.get("from"))) ? num(to.get("from")) / 1000 : 0, e = to != null && !Double.isNaN(num(to.get("to"))) ? num(to.get("to")) / 1000 : 0;
                            if (cur == null || Character.isWhitespace(tx.charAt(0))) { cur = new W(tt, f, e); ws.add(cur); } else { cur.w += tx; cur.e = e; } } } }
                P.segs.add(new Seg(text, s0, e0));
                if (ws != null && !ws.isEmpty()) P.words.addAll(ws); else if (!Double.isNaN(s0) && !Double.isNaN(e0) && !text.isEmpty()) { P.approx = true; P.words.addAll(splitEven(text, s0, e0)); } }
        } else if (list(m.get("chunks")) != null) {
            List<W> ws = mapW(list(m.get("chunks"))); P.words.addAll(ws); if (!ws.isEmpty()) { StringBuilder sb = new StringBuilder(); for (W w : ws) sb.append(sb.length() > 0 ? " " : "").append(w.w); P.segs.add(new Seg(sb.toString(), ws.get(0).s, ws.get(ws.size() - 1).e)); }
        } else throw new IllegalArgumentException("No reconozco el formato del JSON (busco segments, word_segments, transcription o chunks)");
        for (W w : P.words) if (Double.isNaN(w.e) || w.e < w.s) w.e = w.s + .25;
        P.words.sort(Comparator.comparingDouble(w -> w.s));
        if (P.words.isEmpty()) throw new IllegalArgumentException("El JSON no trae palabras con tiempos");
        return P;
    }
    /** Segmento alucinado por Whisper: casi todas sus palabras quedaron con duracion cero en el mismo instante (repeticiones, «Subtitulos realizados por...»). */
    static boolean colapsado(Seg sg) {
        if (sg.words.size() < 4) return false; int cero = 0;
        for (W w : sg.words) if (w.e - w.s < .01) cero++;
        return cero >= sg.words.size() * .8;
    }
    static int lev(String a, String b) {
        int m = a.length(), n = b.length(); if (m == 0 || n == 0) return Math.max(m, n); int[] p = new int[n + 1]; for (int i = 0; i <= n; i++) p[i] = i;
        for (int i = 1; i <= m; i++) { int d = i - 1; p[0] = i; for (int k = 1; k <= n; k++) { int t = p[k]; p[k] = Math.min(Math.min(p[k] + 1, p[k - 1] + 1), d + (a.charAt(i - 1) == b.charAt(k - 1) ? 0 : 1)); d = t; } }
        return p[n];
    }
    static double sim(String a, String b) { if (a.isEmpty() || b.isEmpty()) return -.5; if (a.equals(b)) return 2; double r = 1 - lev(a, b) / (double) Math.max(a.length(), b.length()); return r >= .6 ? 1.5 * r : -1; }
    static List<Word> lyricWords(List<Unit> units) {
        List<Word> out = new ArrayList<>(); Word cur = null;
        for (int k = 0; k < units.size(); k++) { Unit u = units.get(k), pu = k > 0 ? units.get(k - 1) : null;
            if (cur == null || pu == null || pu.line != u.line || pu.pre.length() + pu.txt.length() < u.pre.length()) { cur = new Word(); out.add(cur); }
            cur.units.add(u); cur.txt += u.txt; }
        for (Word w : out) w.n = norm(w.txt); return out;
    }
    static Result align(List<Unit> units, List<W> W) {
        List<Word> L = lyricWords(units); int n = L.size(), m = W.size(); String[] wn = new String[m]; for (int i = 0; i < m; i++) wn[i] = norm(W.get(i).w);
        final double GAP = -.7; int cols = m + 1; float[] sc = new float[(n + 1) * cols]; byte[] tb = new byte[(n + 1) * cols];
        for (int i = 1; i <= n; i++) { sc[i * cols] = (float) (i * GAP); tb[i * cols] = 2; } for (int k = 1; k <= m; k++) { sc[k] = (float) (k * GAP * .5); tb[k] = 3; }
        for (int i = 1; i <= n; i++) for (int k = 1; k <= m; k++) {
            double d = sc[(i - 1) * cols + k - 1] + sim(L.get(i - 1).n, wn[k - 1]), u = sc[(i - 1) * cols + k] + GAP, l = sc[i * cols + k - 1] + GAP * .5; double b = d; byte t = 1;
            if (u > b) { b = u; t = 2; } if (l > b) { b = l; t = 3; } sc[i * cols + k] = (float) b; tb[i * cols + k] = t; }
        int[] match = new int[n]; Arrays.fill(match, -1); int i = n, k = m;
        while (i > 0 || k > 0) { byte t = tb[i * cols + k]; if (t == 1) { if (sim(L.get(i - 1).n, wn[k - 1]) > 0) match[i - 1] = k - 1; i--; k--; } else if (t == 2) i--; else k--; }
        Result R = new Result(); R.L = L; R.n = n; for (int x : match) if (x >= 0) R.hit++;
        Double[] S0 = new Double[n], E0 = new Double[n]; for (int a = 0; a < n; a++) if (match[a] >= 0) { S0[a] = W.get(match[a]).s; E0[a] = W.get(match[a]).e; }
        for (int a = 0; a < n; a++) if (match[a] >= 0 && !Double.isNaN(W.get(match[a]).p)) for (Unit u : L.get(a).units) R.P.put(u, W.get(match[a]).p);   // probabilidad de la palabra del JSON
        int a = 0;
        while (a < n) { if (S0[a] != null) { a++; continue; } int b = a; while (b < n && S0[b] == null) b++;
            if (a > 0 && b < n) { double g0 = E0[a - 1], g1 = S0[b]; double tot = 0; for (int q = a; q < b; q++) tot += Math.max(1, L.get(q).txt.length()); double t = g0;
                if (g1 > g0) for (int q = a; q < b; q++) { double d = (g1 - g0) * Math.max(1, L.get(q).txt.length()) / tot; S0[q] = t; E0[q] = t + d; t += d; R.interp++; } }
            a = b; }
        for (int q = 0; q < n; q++) { if (S0[q] == null) continue; Word w = L.get(q); double tot = 0; for (Unit u : w.units) tot += Math.max(1, u.txt.length()); double c = 0;
            double dd = w.units.size() > 1 ? Math.min(E0[q] - S0[q], .12 + .11 * Math.max(1, w.n.length())) : E0[q] - S0[q];
            for (Unit u : w.units) { R.T.put(u, S0[q] + dd * c / tot); c += Math.max(1, u.txt.length()); } }
        return R;
    }
}
