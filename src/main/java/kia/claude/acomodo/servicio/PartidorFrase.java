package kia.claude.acomodo.servicio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kia.claude.acomodo.interfaces.CriterioCorte;
import kia.claude.acomodo.interfaces.MedidorLinea;
import kia.claude.acomodo.modelo.PalabraCantada;

/**
 * Reparte una frase en lineas que no pasan el limite. Prueba todos los repartos posibles y se queda con el de menor costo
 * (como TeX al acomodar parrafos): pocas lineas, de largo parecido y cortadas donde los criterios lo prefieren.
 */
final class PartidorFrase {
    private static final double COSTO_LINEA = 1;           // cada linea extra cuesta: solo se agrega si el corte lo vale
    private static final double PESO_HOLGURA = 1;          // castiga lineas mucho mas cortas que el limite
    private static final double CASTIGO_PALABRA_SOLA = .5; // una palabra sola en su linea se ve descolgada

    private final MedidorLinea medidor;
    private final List<CriterioCorte> criterios;

    PartidorFrase(MedidorLinea medidor, List<CriterioCorte> criterios) {
        this.medidor = medidor;
        this.criterios = criterios;
    }

    /** Cuantas palabras van en cada linea, en orden. */
    List<Integer> partir(List<PalabraCantada> palabras) {
        int n = palabras.size();
        double[] calificacion = new double[n];                    // calificacion[i]: que tan bueno es cortar despues de la palabra i
        for (int i = 0; i < n - 1; i++) for (CriterioCorte c : criterios) calificacion[i] += c.calificar(palabras, i);

        double[] costo = new double[n + 1];                       // costo[j]: mejor reparto de las primeras j palabras
        int[] inicioLinea = new int[n + 1];                       // donde empieza la ultima linea de ese mejor reparto
        Arrays.fill(costo, Double.POSITIVE_INFINITY);
        costo[0] = 0;
        for (int j = 1; j <= n; j++) {
            String linea = "";
            for (int i = j - 1; i >= 0; i--) {
                linea = linea.isEmpty() ? palabras.get(i).texto() : palabras.get(i).texto() + " " + linea;
                double ocupacion = medidor.ocupacion(linea);
                if (ocupacion > 1 && i < j - 1) break;            // una palabra que sola ya no cabe se acepta: no hay otra opcion
                double c = costo[i] + COSTO_LINEA + PESO_HOLGURA * Math.pow(Math.max(0, 1 - ocupacion), 2)
                    + (j - i == 1 && n > 1 ? CASTIGO_PALABRA_SOLA : 0) - (j < n ? calificacion[j - 1] : 0);
                if (c < costo[j]) { costo[j] = c; inicioLinea[j] = i; }
            }
        }

        List<Integer> tamanos = new ArrayList<>();
        for (int j = n; j > 0; j = inicioLinea[j]) tamanos.add(0, j - inicioLinea[j]);
        return tamanos;
    }
}
