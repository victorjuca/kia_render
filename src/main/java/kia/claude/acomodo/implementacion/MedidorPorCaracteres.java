package kia.claude.acomodo.implementacion;

import kia.claude.acomodo.interfaces.MedidorLinea;

/** Mide con el mismo criterio que la guia naranja del editor de letra: cantidad de caracteres. */
public final class MedidorPorCaracteres implements MedidorLinea {
    private final int maximo;

    public MedidorPorCaracteres(int maximo) { this.maximo = maximo; }

    @Override
    public double ocupacion(String linea) { return linea.length() / (double) maximo; }
}
