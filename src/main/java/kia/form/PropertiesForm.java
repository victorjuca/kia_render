package kia.form;

/**
 * Solo para kia-render: lo poco que el motor copiado de kia lee de PropertiesForm.
 * Los valores los pone kia.render.Recursos al arrancar (carpeta RECURSOS_DIR).
 */
public final class PropertiesForm {

    /** Fondos de siempre (con logo / sin logo); los proyectos solo guardan fondo.png si cambian el fondo. */
    public static String FILE_IMG_CON_FONDO_IA = "recursos/Fondo Karaoke IA.jpg";
    public static String FILE_IMG_SIN_FONDO_IA = "recursos/Fondo Karaoke IA_sin_logo.jpg";
    public static String FILE_SIN_AUDIO = "recursos/sin_audio.mp3";

    private static Double whisperProbabilidad = 0.60;

    private PropertiesForm() {
    }

    public static Double getWhisperProbabilidad() {
        return whisperProbabilidad;
    }

    public static void setWhisperProbabilidad(Double valor) {
        whisperProbabilidad = valor;
    }
}
