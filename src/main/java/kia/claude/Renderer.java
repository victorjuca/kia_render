package kia.claude;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.LineMetrics;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ====================================================================================================
//  RENDERIZADOR (el mismo para la vista previa y para el mp4 exportado)  -  Java2D
// ====================================================================================================
final class Renderer {
    final int W, H;
    BufferedImage bg, frame, slot, topbar, topred, marker;
    List<Block> blocks = new ArrayList<>(); List<double[]> bars = new ArrayList<>(); double dur = 1.0;
    double[] starts = new double[0], ends = new double[0]; List<Unit> marked = new ArrayList<>();
    boolean topDirty = true, showTop = true, cfg = false;
    // estilo
    Font base = new Font(Font.SANS_SERIF, Font.BOLD, 1); Color active, inactive, frameC, iframeC; int lc = 4, size, stroke, lh, mw;
    int[] slotTop = new int[2]; int dotD, ballMax, ballMin, bandH, bandCy, bandTop, aw, ah, bw, bh, bcy, tbX0, tbX1, tbY, tbH, gw;
    Font cfont, tfont;
    private final Map<Integer, Font> fcache = new HashMap<>(); private final LinkedHashMap<String, List<Layer>> lcache = new LinkedHashMap<>();

    static final class Layer { BufferedImage bright, red; int[][] rects; List<Unit> units; double x0; }

    List<Ev> events = new ArrayList<>(); java.util.function.IntFunction<List<Block>> blocksFor; Color bActive, bInactive, bFrame, bIframe; int bLc = 4; double bOx = 0, bOy = 0;
    double dx = 0; boolean italic = false; final List<Renderer> extra = new ArrayList<>();   // alineacion, cursiva y demas pistas de texto
    /** Alineacion de KaraFun: pixeles de una pantalla de 640x480; vertical positiva = hacia arriba. Llamar despues de configure(). */
    void offset(double ox, double oy) { bOx = ox; bOy = oy; double k = H / 480.0; dx = ox * k; int d = (int) Math.round(-oy * k); slotTop[0] += d; slotTop[1] += d; lcache.clear(); }
    void setItalic(boolean it) { italic = it; fcache.clear(); lcache.clear(); }
    Renderer(int W, int H) {
        this.W = W; this.H = H; bg = solid(Color.BLACK);
        frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    }
    private BufferedImage solid(Color c) {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); Graphics2D g = im.createGraphics(); g.setColor(c); g.fillRect(0, 0, W, H); g.dispose(); return im;
    }
    static Graphics2D aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    // ---------------- estilo ----------------
    void configure(Font baseFont, double fsize, Color active, Color inactive, Color frameC, Color iframeC, int lc, boolean showTop) {
        this.bActive = active; this.bInactive = inactive; this.bFrame = frameC; this.bIframe = iframeC; this.bLc = lc; this.base = baseFont; this.active = active; this.inactive = inactive; this.frameC = frameC; this.iframeC = iframeC; this.lc = lc; this.showTop = showTop;
        size = Math.max(10, (int) Math.round(fsize * H / 400.0)); stroke = Math.max(3, size / 12); lh = (int) Math.round(size * U.LINE_SP);
        int y0 = (int) (H * .49 - lc * lh / 2.0); slotTop[0] = y0; slotTop[1] = y0 + 2 * lh;
        fcache.clear(); lcache.clear();
        dotD = Math.max(6, (int) (H * .031)); ballMax = (int) (H * .0875); ballMin = (int) (H * .065);
        bandH = (int) (H * .05); bandCy = (int) (H * .95); bandTop = bandCy - bandH / 2; aw = (int) (H * .04); ah = (int) (H * .045);
        mw = Math.max(12, (int) (size * .28)); marker = new BufferedImage(mw, (int) (size * 1.05), BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < mw; x++) {
            int a = (int) (255 * Math.min(1.0, x / (mw * .6)));
            for (int y = 0; y < marker.getHeight(); y++) marker.setRGB(x, y, (a << 24) | 0xFF0000);
        }
        cfont = base.deriveFont((float) (H * .07)); tfont = base.deriveFont((float) Math.max(10, (int) (H * .026)));
        bw = (int) (W * .8); bh = (int) (H * .055); bcy = (int) (H * .62);
        tbX0 = (int) (W * .066); tbX1 = (int) (W * .932); tbY = (int) (H * .042); tbH = (int) (H * .056); gw = Math.max(4, (int) (W * .006));
        slot = new BufferedImage(W, 2 * lh, BufferedImage.TYPE_INT_ARGB);
        topDirty = true; cfg = true;
    }

    // ---------------- fondo ----------------
    static BufferedImage scale(BufferedImage src, int w, int h) {
        w = Math.max(1, w); h = Math.max(1, h); BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {          // reduce a la mitad por pasos (mejor calidad)
            BufferedImage t = new BufferedImage(cur.getWidth() / 2, cur.getHeight() / 2, BufferedImage.TYPE_INT_RGB); Graphics2D g = t.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(cur, 0, 0, t.getWidth(), t.getHeight(), null); g.dispose(); cur = t;
        }
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.drawImage(cur, 0, 0, w, h, null); g.dispose(); return o;
    }
    private static BufferedImage toRgb(BufferedImage s) {
        if (s.getType() == BufferedImage.TYPE_INT_RGB) return s;
        BufferedImage o = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_RGB); Graphics2D g = o.createGraphics(); g.setColor(Color.BLACK); g.fillRect(0, 0, o.getWidth(), o.getHeight()); g.drawImage(s, 0, 0, null); g.dispose(); return o;
    }
    void setBg(BufferedImage raw, Props p) {
        Color base = U.hex(p.bgColor, Color.BLACK);
        if (raw == null) { bg = solid(base); return; }
        // El fondo ocupa todo el cuadro de salida, incluido su contenido en los bordes.
        BufferedImage out = solid(base);
        int ax = (int) Math.round(p.bgOx * W / 640.0), ay = (int) Math.round(p.bgOy * H / 480.0);
        BufferedImage layer = scale(toRgb(raw), W, H); String mode = p.bgBlend == null ? "Opacity" : p.bgBlend;
        if (mode.equals("Additive") || mode.equals("Multiply") || mode.equals("Screen")) {
            int[] px = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
            for (int y = Math.max(0, ay); y < Math.min(H, ay + H); y++) for (int x = Math.max(0, ax); x < Math.min(W, ax + W); x++) {
                int a = px[y * W + x], b = layer.getRGB(x - ax, y - ay) & 0xFFFFFF, r = 0;
                for (int sh = 16; sh >= 0; sh -= 8) {
                    int va = (a >> sh) & 255, vb = (b >> sh) & 255, v = mode.equals("Additive") ? Math.min(255, va + vb) : mode.equals("Multiply") ? va * vb / 255 : 255 - (255 - va) * (255 - vb) / 255;
                    r |= v << sh;
                }
                px[y * W + x] = r;
            }
        } else { Graphics2D g = out.createGraphics(); g.drawImage(layer, ax, ay, null); g.dispose(); }
        Color tint = U.hex(p.bgTint, Color.WHITE); double ta = U.clamp(p.bgTintA, 0, 100) / 100.0;
        if (!(tint.getRed() == 255 && tint.getGreen() == 255 && tint.getBlue() == 255) && ta > 0) {
            int[] px = ((DataBufferInt) out.getRaster().getDataBuffer()).getData(); int tr = tint.getRed(), tg = tint.getGreen(), tb = tint.getBlue();
            for (int i = 0; i < px.length; i++) {
                int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                r = (int) (r + (r * tr / 255 - r) * ta); g = (int) (g + (g * tg / 255 - g) * ta); b = (int) (b + (b * tb / 255 - b) * ta); px[i] = (r << 16) | (g << 8) | b;
            }
        }
        bg = out;
    }

    // ---------------- datos ----------------
    void setData(List<Block> blocks, List<double[]> bars, List<Unit> units, double dur) {
        this.blocks = new ArrayList<>(blocks); this.bars = new ArrayList<>(bars); this.dur = Math.max(1.0, dur);
        marked = new ArrayList<>(); for (Unit u : units) if (u.on()) marked.add(u);
        marked.sort(Comparator.comparingDouble(u -> u.t)); starts = new double[marked.size()]; ends = new double[marked.size()];
        for (int i = 0; i < marked.size(); i++) { starts[i] = marked.get(i).t; ends[i] = marked.get(i).e; }
        topDirty = true;
    }
    private static int bisectRight(double[] a, double v) { int lo = 0, hi = a.length; while (lo < hi) { int m = (lo + hi) >>> 1; if (v < a[m]) hi = m; else lo = m + 1; } return lo; }

    // ---------------- capas de texto ----------------
    private Font fontFor(String txt) {
        int sz = size;
        while (true) {
            Font f = fcache.computeIfAbsent(sz, k -> italic ? base.deriveFont((float) k).deriveFont(AffineTransform.getShearInstance(-.21, 0)) : base.deriveFont((float) k));
            if (U.adv(f, txt) + 2 * stroke <= W * U.MAX_TEXT_W || sz <= 12) return f;
            sz--;
        }
    }
    private void outlined(Graphics2D g, String txt, Font f, double x, double baseline, Color fill, Color line, double sw) {
        if (txt.isEmpty()) return;
        java.awt.Shape s = new TextLayout(txt, f, U.FRC).getOutline(AffineTransform.getTranslateInstance(x, baseline));
        g.setStroke(new BasicStroke((float) (2 * sw), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(line); g.draw(s); g.setColor(fill); g.fill(s);
    }
    /** Propiedades vigentes en t: aplica los eventos de la pista (colores con desvanecimiento, lineas, alineacion). */
    void applyState(double t) {
        if (events.isEmpty()) return;
        Color a = bActive, i = bInactive, f = bFrame, fi = bIframe; int l = bLc; double ox = bOx, oy = bOy;
        for (Ev e : events) {
            if (e.t > t) break;
            for (Ev.Fx x : e.fx) {
                switch (x.type) {
                    case "ChgColActiveColor", "ChgColInactiveColor", "ChgColFrameColor", "ChgColInactiveFrameColor" -> {
                        Color to = Ev.color(x.p.getOrDefault("TargetColor", "#FFFFFFFF")); double fd = Ev.num(x.p.get("FadeTime"), 0);
                        double u = fd <= 0 ? 1.0 : Math.min(1.0, Math.max(0.0, (t - e.t) / fd));
                        switch (x.type) {
                            case "ChgColActiveColor" -> a = Ev.mix(a, to, u);
                            case "ChgColInactiveColor" -> i = Ev.mix(i, to, u);
                            case "ChgColFrameColor" -> f = Ev.mix(f, to, u);
                            default -> fi = Ev.mix(fi, to, u);
                        }
                    }
                    case "ChgLineCount" -> l = (int) Math.max(1, Math.min(4, Math.round(Ev.num(x.first("LineCount"), l))));
                    case "ChgOffsetX" -> ox = Ev.num(x.first("OffsetX"), ox);
                    case "ChgOffsetY" -> oy = Ev.num(x.first("OffsetY"), oy);
                    default -> { }
                }
            }
        }
        active = a; inactive = i; frameC = f; iframeC = fi; lc = l; double k = H / 480.0; dx = ox * k;
        int y0 = (int) (H * .49 - lc * lh / 2.0) + (int) Math.round(-oy * k); slotTop[0] = y0; slotTop[1] = y0 + 2 * lh;
    }
    private List<Layer> layers(Block b) {
        // la clave incluye como se dividio cada renglon en silabas: un coro repetido puede tener el mismo texto con otra division
        StringBuilder kb = new StringBuilder(); for (Line l : b.lines) { kb.append(l.text).append('\u0001'); for (Unit u : l.units) kb.append(u.txt).append('\u0002'); }
        kb.append(active.getRGB()).append(',').append(inactive.getRGB()).append(',').append(frameC.getRGB()).append(',').append(iframeC.getRGB()).append(',').append(dx); String key = kb.toString();
        List<Layer> c = lcache.get(key); if (c != null) return c;
        List<Layer> L = new ArrayList<>();
        for (Line ln : b.lines) {
            String txt = ln.text; Font lf = fontFor(txt); double x0 = (W - U.adv(lf, txt)) / 2 + dx; Layer ly = new Layer();
            ly.bright = new BufferedImage(W, lh, BufferedImage.TYPE_INT_ARGB); ly.red = new BufferedImage(W, lh, BufferedImage.TYPE_INT_ARGB);
            LineMetrics lm = lf.getLineMetrics(txt, U.FRC); double baseline = lh / 2.0 + (lm.getAscent() - lm.getDescent()) / 2;
            for (int k = 0; k < 2; k++) {
                Graphics2D g = aa(((k == 0 ? ly.bright : ly.red)).createGraphics());
                outlined(g, txt, lf, x0, baseline, k == 0 ? inactive : active, k == 0 ? iframeC : frameC, stroke); g.dispose();
            }
            ly.units = ln.units; ly.x0 = x0; ly.rects = new int[ln.units.size()][];
            for (int i = 0; i < ln.units.size(); i++) { Unit u = ln.units.get(i); ly.rects[i] = new int[]{(int) (x0 + U.adv(lf, u.pre)) - stroke, (int) (x0 + U.adv(lf, u.pre + u.txt)) + stroke}; }
            L.add(ly);
        }
        if (lcache.size() > 24) lcache.remove(lcache.keySet().iterator().next());
        lcache.put(key, L); return L;
    }
    private void buildTop() {
        topbar = new BufferedImage(W, tbH, BufferedImage.TYPE_INT_ARGB); topred = new BufferedImage(W, tbH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D td = topbar.createGraphics(), tr = topred.createGraphics(); int lw = Math.max(1, H / 380);
        td.setColor(new Color(197, 1, 0, 77)); td.fillRect(tbX0, 0, tbX1 - tbX0 + 1, tbH);
        td.setColor(new Color(150, 0, 0)); td.setStroke(new BasicStroke(lw)); td.drawRect(tbX0, 0, tbX1 - tbX0, tbH - 1);
        for (Unit u : marked) {
            int x = tbX0 + (int) (u.t / dur * (tbX1 - tbX0)), y1 = (int) (tbH * .22), y2 = (int) (tbH * .78);
            td.setColor(new Color(215, 215, 215, 240)); td.drawLine(x, y1, x, y2); tr.setColor(new Color(235, 45, 20, 255)); tr.drawLine(x, y1, x, y2);
        }
        td.dispose(); tr.dispose(); topDirty = false;
    }
    private void centered(Graphics2D g, String s, Font f, double cx, double cy, Color fill, Color line, double sw) {
        LineMetrics lm = f.getLineMetrics(s, U.FRC); outlined(g, s, f, cx - U.adv(f, s) / 2, cy + (lm.getAscent() - lm.getDescent()) / 2, fill, line, sw);
    }
    private void dot(Graphics2D g, int x, int y, int d) {
        g.setColor(new Color(110, 0, 0)); g.fillOval(x, y, d, d); int o = Math.max(1, (int) (d * .05)); g.setColor(new Color(255, 0, 0)); g.fillOval(x + o, y + o, d - 2 * o, d - 2 * o);
    }

    // ---------------- un fotograma ----------------
    BufferedImage render(double t) {
        Graphics2D g = aa(frame.createGraphics()); g.drawImage(bg, 0, 0, null);
        drawBlocks(g, t); for (Renderer e : extra) e.drawBlocks(g, t);             // letra de todas las pistas
        drawRest(g, t); g.dispose(); return frame;
    }
    /** Letra de esta pista (bloques visibles en el instante t). */
    void drawBlocks(Graphics2D g, double t) {
        applyState(t); List<Block> bl = (blocksFor != null && lc != bLc) ? blocksFor.apply(lc) : blocks;
        for (Block b : bl) {
            if (!b.timed || !(b.show <= t && t < b.hide)) continue;
            double alpha = Math.min(1.0, (t - b.show) / U.FADE_IN) * (1 - Math.min(1.0, Math.max(0.0, (t - b.last) / Math.max(b.hide - b.last, .05))));
            if (alpha <= 0) continue;
            List<Layer> L = layers(b); Graphics2D s = aa(slot.createGraphics()); s.setComposite(AlphaComposite.Clear); s.fillRect(0, 0, W, 2 * lh); s.setComposite(AlphaComposite.SrcOver);
            for (int j = 0; j < L.size(); j++) {
                Layer ly = L.get(j); List<Unit> lu = b.lines.get(j).units; int y = j * lh; s.drawImage(ly.bright, 0, y, null);   // unidades de ESTE bloque (la capa en cache puede venir de un coro repetido)
                for (int k = 0; k < lu.size(); k++) {
                    Unit u = lu.get(k); int xa = Math.max(0, ly.rects[k][0]), xb = Math.min(W, ly.rects[k][1]);
                    if (u.on() && t > u.t) {
                        double pg = Math.min(1.0, (t - u.t) / Math.max(u.e - u.t, .01)); int wpx = Math.max(1, (int) ((ly.rects[k][1] - ly.rects[k][0]) * pg));
                        int xe = Math.min(W, ly.rects[k][0] + wpx); if (xe > xa) s.drawImage(ly.red, xa, y, xe, y + lh, xa, 0, xe, lh, null);
                    }
                }
                Unit u0 = lu.get(0);
                if (j == 0 && b.g == 0 && u0.on()) {                              // marcador de inicio (solo 1er renglon del grupo de arriba)
                    double tf = u0.t, ts = Math.max(b.show, tf - U.MK_APPROACH);
                    if (ts <= t && t < tf + U.MK_HOLD + U.MK_FADE) {
                        double u = Math.min(1.0, (t - ts) / Math.max(tf - ts, .01)), ma = Math.min(1.0, u / .25) * (1 - Math.min(1.0, Math.max(0.0, (t - tf - U.MK_HOLD) / U.MK_FADE)));
                        int mx = (int) ly.x0 - mw - 2 - (int) ((1 - u) * U.MK_TRAVEL * W), cx = Math.max(0, -mx);
                        if (cx < mw) { s.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) ma)); int my = y + (lh - marker.getHeight()) / 2;
                            s.drawImage(marker, mx + cx, my, mx + mw, my + marker.getHeight(), cx, 0, mw, marker.getHeight(), null); s.setComposite(AlphaComposite.SrcOver); }
                    }
                }
            }
            s.dispose();
            if (alpha < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
            g.drawImage(slot, 0, slotTop[b.g], null); g.setComposite(AlphaComposite.SrcOver);
        }
    }
    /** Barra de carga, barra superior y linea de tiempo inferior (comunes a todas las pistas). */
    private void drawRest(Graphics2D g, double t) {
        double[] bar = null; for (double[] x : bars) if (x[0] <= t && t < x[1]) { bar = x; break; }
        if (bar != null) {                                                        // barra de carga con cuenta regresiva
            double fr = (t - bar[0]) / (bar[1] - bar[0]), ba = Math.min(1.0, (bar[1] - t) / U.LEAD_P), rem = bar[1] - t; int bx = (W - bw) / 2, by = bcy - bh / 2;
            if (ba < 1) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) ba));
            g.setColor(new Color(140, 140, 150)); g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 10, 10));
            if (fr > .01) { g.setColor(new Color(143, 26, 16)); g.fill(new RoundRectangle2D.Double(bx, by, Math.max(6, (int) (bw * fr)), bh, 10, 10)); }
            if (rem <= 3) centered(g, String.valueOf(Math.max(1, (int) Math.ceil(rem))), cfont, bx + bw / 2.0, by + bh / 2.0, Color.WHITE, Color.BLACK, 2);
            g.setComposite(AlphaComposite.SrcOver);
        }
        if (showTop) {                                                           // barra superior de progreso + tiempo restante
            if (topDirty) buildTop();
            g.drawImage(topbar, 0, tbY, null);
            int px = tbX0 + (int) (Math.min(1.0, Math.max(0.0, t / dur)) * (tbX1 - tbX0));
            if (px > 0) g.drawImage(topred, 0, tbY, px, tbY + tbH, 0, 0, px, tbH, null);
            g.setColor(new Color(150, 200, 90)); g.fillRect(px - gw / 2, tbY + 2, gw + 1, tbH - 4);
            int rem = Math.max(0, (int) Math.ceil(dur - t)); String lab = (rem / 60) + ":" + String.format("%02d", rem % 60);
            LineMetrics lm = tfont.getLineMetrics(lab, U.FRC); outlined(g, lab, tfont, tbX1 + (int) (W * .01), tbY + tbH / 2.0 + (lm.getAscent() - lm.getDescent()) / 2, Color.WHITE, Color.BLACK, 2);
        }
        g.setColor(new Color(62, 67, 74, 205)); g.fillRect(0, bandTop, W, bandH);                 // linea de tiempo inferior
        g.setStroke(new BasicStroke(1)); g.setColor(new Color(135, 140, 148, 235)); g.drawLine(0, bandTop, W, bandTop); g.drawLine(0, bandTop + bandH - 1, W, bandTop + bandH - 1);
        int dy = (int) Math.round(U.ARROW_A * H * (1 - Math.cos(2 * Math.PI * t / U.ARROW_T)) / 2), ax = (int) (U.ARROW_X * W);
        int sw = (int) (aw * .5), hh = (int) (ah * .5); g.setColor(Color.WHITE);
        int tx = ax - aw / 2, ty = bandTop - ah + 1 + dy;                                          // flecha de arriba (apunta abajo)
        g.fillRect(tx + (aw - sw) / 2, ty, sw, ah - hh); g.fillPolygon(new int[]{tx, tx + aw, tx + aw / 2}, new int[]{ty + ah - hh, ty + ah - hh, ty + ah}, 3);
        int by2 = bandTop + bandH - 1 - dy;                                                         // flecha de abajo (apunta arriba)
        g.fillRect(tx + (aw - sw) / 2, by2 + hh, sw, ah - hh); g.fillPolygon(new int[]{tx, tx + aw, tx + aw / 2}, new int[]{by2 + hh, by2 + hh, by2}, 3);
        double hitX = U.HIT_X * W, vel = U.SPEED * W; int i = bisectRight(starts, t);
        for (int k = i; k < starts.length; k++) { double x = hitX + (starts[k] - t) * vel; if (x - dotD > W) break; dot(g, (int) (x - dotD / 2.0), bandCy - dotD / 2, dotD); }
        int j = i - 1;
        if (j >= 0 && t < ends[j]) { double pg = (t - starts[j]) / Math.max(ends[j] - starts[j], .01); int bd = (int) (ballMax - (ballMax - ballMin) * Math.min(1.0, pg)); dot(g, (int) (hitX - bd / 2.0), bandCy - bd / 2, bd); }
    }
}
