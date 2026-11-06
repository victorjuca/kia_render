package kia.claude;

import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Entrada publica (sin ventanas) al motor de Karaoke Studio para kia-render.
 * Es el mismo render que KaraokeStudio.renderizarMp4 de kia: 1280x720, 25 fps, CRF 27.
 */
public final class MotorRender {

    public static final int ANCHO = 1280, ALTO = 720, FPS = 25, CRF = 27;

    private MotorRender() {
    }

    /** Renderiza el songini.txt (o .kfn) a MP4 con el audio principal del proyecto. */
    public static boolean renderizarMp4(File kfn, File mp4, Consumer<String> progreso, BooleanSupplier cancelado) throws Exception {
        Studio st = new Studio();
        try {
            avisa(progreso, "Abriendo " + kfn.getName());
            st.openKfn(kfn);
            if (st.audioBytes == null) {
                throw new IOException("El proyecto no trae audio: " + kfn);
            }
            if (st.markedList().isEmpty()) {
                throw new IOException("El proyecto no tiene palabras digitadas: " + kfn);
            }
            Renderer r = PreviewPanel.build(st, ANCHO, ALTO);
            String ext = st.audioName.contains(".") ? st.audioName.substring(st.audioName.lastIndexOf('.')) : ".mp3";
            int[] ultimo = {-1};
            return Mp4.export(r, st.audioBytes, ext, mp4, FPS, CRF, f -> {
                int pct = (int) (f * 100);
                if (pct != ultimo[0] && pct % 5 == 0) {
                    ultimo[0] = pct;
                    avisa(progreso, "Renderizando: " + pct + "%");
                }
            }, cancelado);
        } finally {
            st.engine.close();
        }
    }

    /** Texto del Song.ini del proyecto (songini.txt o el que va dentro de un .kfn). */
    public static String songIni(File kfn) throws IOException {
        return ProyectoSongIni.leeSongIni(kfn);
    }

    private static void avisa(Consumer<String> progreso, String m) {
        if (progreso != null) {
            progreso.accept(m);
        }
    }
}
