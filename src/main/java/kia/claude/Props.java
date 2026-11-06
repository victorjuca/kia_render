package kia.claude;

/** Propiedades de estilo (las mismas que el Song.ini de KaraFun). */
final class Props {
    String font = "Arial Black"; int fsize = 50;
    String active = "#FF0000", inactive = "#FFFFFF", frame = "#000000", iframe = "#000000";
    int fill = 1, preview = 1, fixed = 0, lines = 4, ox = 0, oy = 0; String ftype = "Frame2"; boolean bold = false, italic = false;
    String bgColor = "#000000", bgTint = "#FFFFFF", bgBlend = "Opacity"; int bgTintA = 100, bgOx = 0, bgOy = 0, bgDepth = 0;
    /** alfa (hex) de los 4 colores, se conserva tal cual al abrir/guardar */
    String aActive = "FF", aInactive = "FF", aFrame = "FF", aIframe = "FF";
    /** copia el estilo de texto (no el fondo, que es comun a todas las pistas) */
    void copyStyle(Props o) { font = o.font; fsize = o.fsize; active = o.active; inactive = o.inactive; frame = o.frame; iframe = o.iframe; fill = o.fill; preview = o.preview; fixed = o.fixed; lines = o.lines; ox = o.ox; oy = o.oy; ftype = o.ftype; bold = o.bold; italic = o.italic;
        aActive = o.aActive; aInactive = o.aInactive; aFrame = o.aFrame; aIframe = o.aIframe; }
    String styleKey() { return font + fsize + bold + italic + preview + ox + ',' + oy + active + inactive + frame + iframe + lines + aActive + aInactive + aFrame + aIframe; }
    String bgKey() { return bgColor + bgTint + bgTintA + bgBlend + bgOx + bgOy; }
}
