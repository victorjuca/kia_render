package kia.claude;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evento de una pista (carril "eventos"): AnimN=<centesimas>|Tipo:Clave=Valor,Clave=Valor|Tipo2:... */
final class Ev {
    static final class Fx { String type; final Map<String, String> p = new LinkedHashMap<>(); String first(String key) { String v = p.get(key); return v != null ? v : p.values().stream().findFirst().orElse(null); } }
    double t; final List<Fx> fx = new ArrayList<>();
    static Ev parse(String v) {
        if (v == null) return null; String[] parts = v.split("\\|"); Ev e = new Ev();
        try { e.t = Integer.parseInt(parts[0].trim()) / 100.0; } catch (NumberFormatException ex) { return null; }
        for (int i = 1; i < parts.length; i++) {
            String x = parts[i]; if (x.isBlank()) continue; Fx f = new Fx(); int c = x.indexOf(':'); f.type = (c < 0 ? x : x.substring(0, c)).trim();
            if (c >= 0) for (String kv : x.substring(c + 1).split(",")) { int q = kv.indexOf('='); if (q > 0) f.p.put(kv.substring(0, q).trim(), kv.substring(q + 1).trim()); }
            e.fx.add(f);
        }
        return e;
    }
    String str() {
        StringBuilder sb = new StringBuilder().append(Math.round(t * 100));
        for (Fx f : fx) { sb.append('|').append(f.type); if (!f.p.isEmpty()) { sb.append(':'); boolean first = true; for (Map.Entry<String, String> en : f.p.entrySet()) { if (!first) sb.append(','); first = false; sb.append(en.getKey()).append('=').append(en.getValue()); } } }
        return sb.toString();
    }
    static double num(String s, double d) { try { return s == null ? d : Double.parseDouble(s.trim()); } catch (Exception e) { return d; } }
    static Color color(String v) {
        String h = v.startsWith("#") ? v.substring(1) : v; while (h.length() < 8) h += "F";
        return new Color(Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16), Integer.parseInt(h.substring(6, 8), 16));
    }
    static Color mix(Color a, Color b, double u) {
        if (u >= 1) return b;
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * u), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * u),
                         (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * u), (int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * u));
    }
}
