package kia.claude.acomodo.implementacion;

import java.util.List;
import java.util.Set;
import kia.claude.acomodo.interfaces.CriterioCorte;
import kia.claude.acomodo.modelo.PalabraCantada;

/** Castiga terminar una linea en una palabra que se apoya en la siguiente: «y», «con», «de», «la», «mi»... */
public final class CriterioPalabrasEnlace implements CriterioCorte {
    private static final double CASTIGO = 1;
    private static final Set<String> ENLACES = Set.of(
        "y", "e", "o", "u", "ni", "que", "si", "pero", "porque", "cuando",
        "a", "al", "de", "del", "en", "con", "por", "para", "sin", "sobre", "entre", "hasta", "desde",
        "el", "la", "los", "las", "lo", "un", "una", "unos", "unas",
        "mi", "mis", "tu", "tus", "su", "sus", "nuestro", "nuestra", "nuestros", "nuestras",
        "me", "te", "se", "le", "les", "nos");

    @Override
    public double calificar(List<PalabraCantada> palabras, int i) {
        String palabra = palabras.get(i).texto().toLowerCase().replaceAll("[^\\p{L}]", "");   // conserva acentos: «mí» y «él» si pueden cerrar linea
        return ENLACES.contains(palabra) ? -CASTIGO : 0;
    }
}
