package kia.claude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Lyr {
    static final class Parsed { List<Page> pages = new ArrayList<>(); List<Line> lines = new ArrayList<>(); List<Unit> units = new ArrayList<>(); }

    static Parsed parse(String text) {
        Parsed r = new Parsed(); Page cur = null;
        for (String raw : text.split("\n", -1)) {
            String s = raw.strip();
            if (s.isEmpty()) { cur = null; continue; }
            String[] toks = s.split("\\s+");
            Line line = new Line(); line.raw = String.join(" ", toks); StringBuilder pre = new StringBuilder();
            for (String tok : toks) {
                for (String sy : tok.split("/")) {
                    if (sy.isEmpty()) continue;
                    line.units.add(new Unit(sy, pre.toString(), line)); pre.append(sy);
                }
                pre.append(' ');
            }
            if (line.units.isEmpty()) continue;
            line.text = pre.length() > 0 && pre.charAt(pre.length() - 1) == ' ' ? pre.substring(0, pre.length() - 1) : pre.toString();
            if (cur == null) { cur = new Page(); r.pages.add(cur); }
            line.page = r.pages.size() - 1; line.idx = r.lines.size();
            cur.lines.add(line); r.lines.add(line);
            for (Unit u : line.units) { u.i = r.units.size(); r.units.add(u); }
        }
        return r;
    }
    /**
     * Conserva los tiempos al editar la letra. Lo igual al inicio y al final (sin contar mayusculas ni signos) se copia tal cual; en la parte cambiada se alinean las palabras
     * (sin mayusculas ni signos: «hombre» = «hombre.»), una palabra reescrita hereda el tiempo de la que reemplaza y una palabra NUEVA se
     * acomoda en la linea de tiempo entre la anterior y la siguiente (las demas no se mueven).
     */
    static void mergeTimes(List<Unit> old, List<Unit> nw) {
        int m = Math.min(old.size(), nw.size()), a = 0;
        while (a < m && Studio.normU(old.get(a).txt).equals(Studio.normU(nw.get(a).txt))) a++;
        int b = 0;
        while (b < m - a && Studio.normU(old.get(old.size() - 1 - b).txt).equals(Studio.normU(nw.get(nw.size() - 1 - b).txt))) b++;
        for (int i = 0; i < a; i++) { nw.get(i).t = old.get(i).t; nw.get(i).revisar = old.get(i).revisar; nw.get(i).prob = old.get(i).prob; }
        for (int i = 0; i < b; i++) { Unit o = old.get(old.size() - 1 - i), n = nw.get(nw.size() - 1 - i); n.t = o.t; n.revisar = o.revisar; n.prob = o.prob; }
        int o0 = a, n0 = a, no = old.size() - b - a, nn = nw.size() - b - a;
        if (nn == 0 || (long) no * nn > 4_000_000L) return;
        String[] ko = new String[no], kn = new String[nn];
        for (int i = 0; i < no; i++) ko[i] = Studio.normU(old.get(o0 + i).txt);
        for (int j = 0; j < nn; j++) kn[j] = Studio.normU(nw.get(n0 + j).txt);
        int[][] L = new int[no + 1][nn + 1];                                    // subsecuencia comun mas larga de la parte cambiada
        for (int i = no - 1; i >= 0; i--) for (int j = nn - 1; j >= 0; j--) L[i][j] = ko[i].equals(kn[j]) ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
        if (Math.max(no, nn) > 40 && L[0][0] * 2 < Math.max(no, nn)) return;     // es otra letra (p. ej. se pego una nueva): no heredar tiempos
        boolean[] nuevo = new boolean[nw.size()]; List<Integer> go = new ArrayList<>(), gn = new ArrayList<>(); int i = 0, j = 0;
        while (i < no || j < nn) {
            if (i < no && j < nn && ko[i].equals(kn[j]) && L[i][j] == L[i + 1][j + 1] + 1) {
                pair(old, nw, o0, n0, go, gn, nuevo); nw.get(n0 + j).t = old.get(o0 + i).t; i++; j++;
            } else if (j < nn && (i == no || L[i][j + 1] >= L[i + 1][j])) gn.add(j++);
            else go.add(i++);
        }
        pair(old, nw, o0, n0, go, gn, nuevo);
        for (int s = 0; s < nw.size(); s++) {                                   // palabras nuevas: repartidas entre la anterior y la siguiente digitadas
            if (!nuevo[s]) continue; int e = s; while (e + 1 < nw.size() && nuevo[e + 1]) e++;
            Unit p = s > 0 ? nw.get(s - 1) : null, n = e + 1 < nw.size() ? nw.get(e + 1) : null;
            if (p != null && n != null && p.on() && n.on() && n.t > p.t) for (int k = s; k <= e; k++) nw.get(k).t = U.r3(p.t + (n.t - p.t) * (k - s + 1) / (e - s + 2));
            s = e;
        }
    }
    /** Un tramo cambiado: las palabras reescritas heredan el tiempo en orden; las que sobran en la letra nueva quedan como agregadas. */
    private static void pair(List<Unit> old, List<Unit> nw, int o0, int n0, List<Integer> go, List<Integer> gn, boolean[] nuevo) {
        int k = 0; for (; k < Math.min(go.size(), gn.size()); k++) nw.get(n0 + gn.get(k)).t = old.get(o0 + go.get(k)).t;
        for (; k < gn.size(); k++) nuevo[n0 + gn.get(k)] = true;
        go.clear(); gn.clear();
    }
    static void computeEnds(List<Unit> units) {
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            if (!u.on()) { u.e = Double.NaN; continue; }
            Unit n = i + 1 < units.size() ? units.get(i + 1) : null;
            if (n == null || !n.on()) u.e = u.t + .9;
            else u.e = (n.t - u.t <= 1.2) ? n.t : u.t + .5;
        }
    }
    static void buildBlocks(List<Page> pages, int lc, boolean preview, List<Block> blocks, List<double[]> bars) {
        blocks.clear(); bars.clear();
        final int per = lc == 1 ? 1 : 2;                                  // renglones por bloque
        for (Page pg : pages) for (int j = 0; j < pg.lines.size(); j += per) blocks.add(new Block(new ArrayList<>(pg.lines.subList(j, Math.min(j + per, pg.lines.size())))));
        for (int k = 0; k < blocks.size(); k++) {
            Block b = blocks.get(k); b.k = k; b.timed = b.units.get(0).on(); b.first = b.units.get(0).t; b.last = Double.NaN;
            for (Unit u : b.units) if (u.on()) b.last = Double.isNaN(b.last) ? u.e : Math.max(b.last, u.e);
            b.g = lc == 3 ? (b.lines.size() == 2 ? 0 : 1) : lc == 4 ? k % 2 : 0;     // 4: arriba/abajo alternados; 3: 2+1; 1 y 2: un grupo
        }
        List<Block> timed = new ArrayList<>(); for (Block b : blocks) if (b.timed) timed.add(b);
        for (int i = 0; i < timed.size(); i++) {
            Block b = timed.get(i), nxt = null;
            for (int j = i + 1; j < timed.size(); j++) if (timed.get(j).g == b.g) { nxt = timed.get(j); break; }
            b.hide = b.last + U.FADE_OUT;
            if (nxt != null) b.hide = Math.min(b.hide, Math.max(b.last + .05, nxt.first - .25));
        }
        Map<Integer, Double> lastG = new HashMap<>();
        for (int i = 0; i < timed.size(); i++) {
            Block b = timed.get(i);
            b.show = Math.max(lastG.getOrDefault(b.g, -1e9), b.first - U.LEAD_P);
            if (!preview && i > 0) b.show = Math.max(b.show, timed.get(i - 1).last);
            lastG.put(b.g, b.hide);
            if (i == 0) { if (b.first > 1.5) bars.add(new double[]{0.0, b.first}); }
            else if (b.first - timed.get(i - 1).last >= U.GAP) bars.add(new double[]{timed.get(i - 1).last + U.BAR_DELAY, b.first});
        }
    }
}
