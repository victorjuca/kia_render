package kia.claude.acomodo.modelo;

/** Palabra de la letra con el tiempo en que se canta, en segundos (NaN si no se conoce). */
public record PalabraCantada(String texto, double inicio, double fin) {

    public double duracion() { return fin - inicio; }

    /** Letras y numeros sin signos: sirve para estimar cuanto deberia durar al cantarse. */
    public int letras() { return Math.max(1, texto.replaceAll("[^\\p{L}\\p{N}]", "").length()); }
}
