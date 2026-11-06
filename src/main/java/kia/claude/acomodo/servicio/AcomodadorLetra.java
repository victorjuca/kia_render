package kia.claude.acomodo.servicio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kia.claude.acomodo.interfaces.CriterioCorte;
import kia.claude.acomodo.interfaces.MedidorLinea;
import kia.claude.acomodo.modelo.Frase;
import kia.claude.acomodo.modelo.PalabraCantada;

/**
 * Arma el texto de la letra a partir de frases con tiempos: 
 * cada frase se reparte en lineas que caben en el karaoke,
 * un silencio largo abre una estrofa nueva (linea en blanco) y una frase repetida (estribillo) se acomoda siempre igual.
 */
public final class AcomodadorLetra {
    private static final double SILENCIO_ESTROFA = 4;      // seg sin cantar entre dos frases que separan estrofas

    private final PartidorFrase partidor;

    public AcomodadorLetra(MedidorLinea medidor, List<CriterioCorte> criterios) {
        this.partidor = new PartidorFrase(medidor, criterios);
    }

    public String acomodar(List<Frase> frases) {
        StringBuilder letra = new StringBuilder();
        Map<String, List<Integer>> acomodoPorFrase = new HashMap<>();   // la primera vez que aparece una frase decide como se acomoda
        Frase anterior = null;
        for (Frase frase : frases) {
            if (anterior != null) letra.append(frase.inicio() - anterior.fin() >= SILENCIO_ESTROFA ? "\n\n" : "\n");
            List<Integer> tamanos = acomodoPorFrase.computeIfAbsent(frase.clave(), k -> partidor.partir(frase.palabras()));
            escribir(letra, frase.palabras(), tamanos);
            anterior = frase;
        }
        return letra.toString();
    }

    private static void escribir(StringBuilder letra, List<PalabraCantada> palabras, List<Integer> tamanos) {
        int k = 0;
        for (int linea = 0; linea < tamanos.size(); linea++) {
            if (linea > 0) letra.append('\n');
            for (int p = 0; p < tamanos.get(linea); p++, k++) {
                String texto = palabras.get(k).texto();
                letra.append(p > 0 ? " " : "").append(k == 0 ? capitalizar(texto) : texto);
            }
        }
    }

    /** Primera letra en mayuscula, saltando signos de apertura como ¿ o ¡. */
    private static String capitalizar(String texto) {
        for (int i = 0; i < texto.length(); i++) {
            if (Character.isLetter(texto.charAt(i))) return texto.substring(0, i) + Character.toUpperCase(texto.charAt(i)) + texto.substring(i + 1);
        }
        return texto;
    }
}
