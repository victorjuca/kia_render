package kia.claude.acomodo.implementacion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kia.claude.acomodo.interfaces.CriterioCorte;
import kia.claude.acomodo.modelo.Frase;
import kia.claude.acomodo.modelo.PalabraCantada;

/**
 * Premia cortar donde el cantante hace pausa. Whisper casi no deja silencio entre palabras: la pausa queda dentro de la
 * palabra (dura mucho mas de lo que corresponde a sus letras) o, a veces, en el inicio adelantado de la siguiente.
 */
public final class CriterioPausa implements CriterioCorte {
    private static final double RITMO_TIPICO = .15;      // seg por letra si la cancion no trae duraciones
    private static final double SILENCIO_CLARO = .6;     // seg de silencio que ya cuentan como pausa completa
    private static final double EXCESO_CLARO = 1.5;      // durar 2.5 veces lo esperado cuenta como pausa completa
    private static final double PESO_SIGUIENTE = .5;     // el alargamiento de la palabra siguiente es una pista mas debil

    private final double segundosPorLetra;

    public CriterioPausa(double segundosPorLetra) { this.segundosPorLetra = segundosPorLetra; }

    /** Mide el ritmo en la propia cancion (mediana de segundos por letra), para que una balada y una cancion rapida se traten igual. */
    public static CriterioPausa segunRitmo(List<Frase> frases) {
        List<Double> ritmos = new ArrayList<>();
        for (Frase f : frases) for (PalabraCantada p : f.palabras()) if (p.duracion() > 0) ritmos.add(p.duracion() / p.letras());
        Collections.sort(ritmos);
        return new CriterioPausa(ritmos.isEmpty() ? RITMO_TIPICO : ritmos.get(ritmos.size() / 2));
    }

    @Override
    public double calificar(List<PalabraCantada> palabras, int i) {
        PalabraCantada actual = palabras.get(i), siguiente = palabras.get(i + 1);
        double silencio = acotar((siguiente.inicio() - actual.fin()) / SILENCIO_CLARO);
        return Math.max(silencio, Math.max(alargamiento(actual), PESO_SIGUIENTE * alargamiento(siguiente)));
    }

    /** 0 si la palabra dura lo normal, 1 si dura tanto que claramente esconde una pausa. */
    private double alargamiento(PalabraCantada p) {
        double esperado = p.letras() * segundosPorLetra;
        return acotar((p.duracion() / esperado - 1) / EXCESO_CLARO);
    }

    /** Lleva el valor a [0, 1]; sin tiempos (NaN) no hay pausa. */
    private static double acotar(double v) { return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v)); }
}
