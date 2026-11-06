package kia.claude;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.JPanel;

/** Vista previa: el mismo renderizador que usa la exportacion. */
final class PreviewPanel extends JPanel {
    final Studio st; Renderer R; BufferedImage img; String kStyle, kBg, kData; double kT = -1; final int RW = 720, RH = 405;
    PreviewPanel(Studio st) { this.st = st; setBackground(Color.BLACK); setPreferredSize(new Dimension(640, 360)); setMinimumSize(new Dimension(320, 180)); }
    /** Un renderizador por pista de texto (la primera dibuja ademas lo comun: fondo, barras y linea de tiempo). fsize en el estudio = tamano tal cual. */
    static Renderer build(Studio st, int W, int H) {
        st.stash(); Renderer first = null;
        for (Studio.Tk tk : st.tracks) {
            Props p = tk.q; Renderer r = new Renderer(W, H);
            r.configure(U.baseFont(p.font), p.fsize, U.withAlpha(p.active, p.aActive), U.withAlpha(p.inactive, p.aInactive), U.withAlpha(p.frame, p.aFrame), U.withAlpha(p.iframe, p.aIframe), p.lines, true);
            r.offset(p.ox, p.oy); r.setItalic(p.italic); r.events = tk.events; r.blocksFor = tk::blocksFor;
            if (first == null) { first = r; r.setBg(st.bgImg, st.props); } else first.extra.add(r);
        }
        feed(first, st); return first;
    }
    static List<double[]> globalBars(Studio st) {
        if (st.tracks.size() == 1) return st.tracks.get(0).bars;
        List<Block> tb = new ArrayList<>(); for (Studio.Tk t : st.tracks) for (Block b : t.blocks) if (b.timed && !Double.isNaN(b.last)) tb.add(b);
        tb.sort(Comparator.comparingDouble(b -> b.first)); List<double[]> bars = new ArrayList<>(); double last = Double.NaN;
        for (int i = 0; i < tb.size(); i++) {
            Block b = tb.get(i);
            if (i == 0) { if (b.first > 1.5) bars.add(new double[]{0.0, b.first}); }
            else if (b.first - last >= U.GAP) bars.add(new double[]{last + U.BAR_DELAY, b.first});
            last = Double.isNaN(last) ? b.last : Math.max(last, b.last);
        }
        return bars;
    }
    /** Actualiza los datos (bloques, marcas) de todos los renderizadores. */
    static void feed(Renderer first, Studio st) {
        st.stash(); List<Unit> all = new ArrayList<>(); for (Studio.Tk t : st.tracks) all.addAll(t.units);
        for (int i = 0; i < st.tracks.size(); i++) {
            Studio.Tk t = st.tracks.get(i); Renderer r = i == 0 ? first : first.extra.get(i - 1);
            if (i == 0) r.setData(t.blocks, globalBars(st), all, st.dur); else r.setData(t.blocks, new ArrayList<>(), t.units, st.dur);
        }
    }
    String styleKey() { StringBuilder sb = new StringBuilder(); for (Studio.Tk t : st.tracks) sb.append(t == st.tk() ? st.props.styleKey() : t.q.styleKey()).append('|').append(System.identityHashCode(t.events)).append(';'); return sb.toString(); }
    void refresh(boolean force) {
        try {
            Props q = st.props; String ks = styleKey(), kb = System.identityHashCode(st.bgImg) + q.bgKey(), kd = st.ver + ":" + st.dur; boolean dirty = force; double t = U.r3(st.pos());
            if (R == null || !ks.equals(kStyle)) { R = build(st, RW, RH); kStyle = ks; kBg = kb; kData = kd; dirty = true; }
            if (!kb.equals(kBg)) { R.setBg(st.bgImg, q); kBg = kb; dirty = true; }
            if (!kd.equals(kData)) { feed(R, st); kData = kd; dirty = true; }
            if (t != kT || dirty || img == null) { img = R.render(t); kT = t; repaint(); }
        } catch (Exception e) { e.printStackTrace(); }
    }
    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0); if (img == null) return; Graphics2D g = (Graphics2D) g0; g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double s = Math.min(getWidth() / (double) img.getWidth(), getHeight() / (double) img.getHeight()); int w = (int) (img.getWidth() * s), h = (int) (img.getHeight() * s);
        g.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
    }
}
