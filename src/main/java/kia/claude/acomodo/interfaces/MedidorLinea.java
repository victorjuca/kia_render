package kia.claude.acomodo.interfaces;

/** Mide si una linea cabe en el karaoke sin que haya que achicar la letra. */
public interface MedidorLinea {

    /** Fraccion del ancho maximo que ocupa la linea: mayor que 1 significa que pasa el limite. */
    double ocupacion(String linea);
}
