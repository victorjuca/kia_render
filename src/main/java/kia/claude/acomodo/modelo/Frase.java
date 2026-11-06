package kia.claude.acomodo.modelo;

import java.util.List;
import java.util.stream.Collectors;

/** Palabras que se cantan de corrido (un segmento de Whisper). Su inicio y su fin siempre son corte de linea. */
public record Frase(List<PalabraCantada> palabras) {

    public double inicio() { return palabras.get(0).inicio(); }

    public double fin() { return palabras.get(palabras.size() - 1).fin(); }

    /** Igual para dos frases con la misma letra aunque cambien mayusculas o signos: asi se reconoce un estribillo. */
    public String clave() {
        return palabras.stream().map(p -> p.texto().toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "")).collect(Collectors.joining(" "));
    }
}
