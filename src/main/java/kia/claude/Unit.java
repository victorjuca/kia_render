package kia.claude;

// ====================================================================================================
//  LETRA -> UNIDADES (palabras / silabas separadas por "/")  Y  BLOQUES DE PANTALLA
// ====================================================================================================
final class Unit {
    final String txt, pre; double t = Double.NaN, e = Double.NaN; final Line line; int i = -1;
    Unit(String txt, String pre, Line line) { this.txt = txt; this.pre = pre; this.line = line; }
    /** Importada de Whisper con probabilidad menor a WHISPER_PROBABILIDAD: se dibuja en rojo en la linea de tiempo para revisarla. */
    boolean revisar; double prob = Double.NaN;
    boolean on() { return !Double.isNaN(t); }
}
