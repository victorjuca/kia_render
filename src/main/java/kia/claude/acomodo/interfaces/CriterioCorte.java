package kia.claude.acomodo.interfaces;

import java.util.List;
import kia.claude.acomodo.modelo.PalabraCantada;

/** Califica cortar la linea despues de la palabra i de una frase: positivo es buen lugar, negativo es mal lugar. */
public interface CriterioCorte {

    /** Solo se llama con i menor que la ultima posicion: siempre hay una palabra siguiente. */
    double calificar(List<PalabraCantada> palabras, int i);
}
