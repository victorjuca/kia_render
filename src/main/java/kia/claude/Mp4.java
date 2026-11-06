package kia.claude;

import java.awt.image.DataBufferInt;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Exporta el video MP4 con ffmpeg a partir de un Renderer ya configurado. */
final class Mp4 {
    interface Progress { void at(double f); }
    static boolean export(Renderer R, byte[] audio, String ext, File dst, int fps, int crf, Progress prog, java.util.function.BooleanSupplier cancelled) throws Exception {
        File ff = U.which("ffmpeg"), fp = U.which("ffprobe");
        if (ff == null || fp == null) throw new IOException("No se encontro ffmpeg/ffprobe en el PATH (instala ffmpeg).");
        File tmp = Files.createTempDirectory("kst").toFile(); File ap = new File(tmp, "a" + (ext == null || ext.isEmpty() ? ".mp3" : ext));
        try {
            Files.write(ap.toPath(), audio);
            String dur = new String(U.runFfmpeg(List.of(fp.getPath(), "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", ap.getPath()), null), StandardCharsets.UTF_8).trim();
            double total = Double.parseDouble(dur); int n = (int) (total * fps);
            List<String> cmd = List.of(ff.getPath(), "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", R.W + "x" + R.H, "-r", String.valueOf(fps), "-i", "-", "-i", ap.getPath(),
                "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "medium", "-tune", "animation", "-crf", String.valueOf(crf), "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                "-c:a", "aac", "-b:a", "128k", "-shortest", dst.getPath());
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            boolean terminado = false;
            try {
                OutputStream os = new BufferedOutputStream(pr.getOutputStream(), 1 << 20); byte[] rgb = new byte[R.W * R.H * 3];
                for (int f = 0; f < n; f++) {
                    if (cancelled != null && cancelled.getAsBoolean()) return false;
                    int[] px;
                    try { px = ((DataBufferInt) R.render(f / (double) fps).getRaster().getDataBuffer()).getData(); }
                    catch (RuntimeException e) { throw new IOException("Error al dibujar el segundo " + String.format(java.util.Locale.ROOT, "%.2f", f / (double) fps) + ": " + e, e); }
                    for (int i = 0, k = 0; i < px.length; i++) { int c = px[i]; rgb[k++] = (byte) (c >> 16); rgb[k++] = (byte) (c >> 8); rgb[k++] = (byte) c; }
                    os.write(rgb); if (prog != null && f % 5 == 0) prog.at(f / (double) n);
                }
                os.close(); if (pr.waitFor() != 0) throw new IOException("ffmpeg termino con error");
                terminado = true;
                return true;
            } finally {
                // si se cancelo o fallo a la mitad, cierra ffmpeg para que no se quede con el archivo bloqueado
                if (!terminado) { try { pr.getOutputStream().close(); } catch (IOException ignored) { } pr.destroyForcibly(); pr.waitFor(); }
            }
        } finally { try { Files.deleteIfExists(ap.toPath()); tmp.delete(); } catch (Exception ignored) { } }
    }
}
