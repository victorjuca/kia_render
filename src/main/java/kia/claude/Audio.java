package kia.claude;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// ====================================================================================================
//  AUDIO  (decodifica con ffmpeg; reproduce con javax.sound; velocidad sin cambiar el tono con atempo)
// ====================================================================================================
final class Audio {
    /** bytes de un mp3/wav/ogg... -> float[] estereo intercalado (L,R,L,R...) a 44100 Hz. */
    static float[] decode(byte[] data, String ext) throws Exception {
        File ff = U.which("ffmpeg");
        if (ff == null) throw new IOException("No se encontro ffmpeg en el PATH (instalalo con: winget install Gyan.FFmpeg).");
        File tmp = Files.createTempDirectory("kst").toFile(); File p = new File(tmp, "in" + (ext == null || ext.isEmpty() ? ".mp3" : ext));
        try {
            Files.write(p.toPath(), data);
            byte[] out = U.runFfmpeg(List.of(ff.getPath(), "-loglevel", "error", "-i", p.getPath(), "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "2", "-ar", String.valueOf(U.SR), "pipe:1"), null);
            return toFloats(out);
        } finally { try { Files.deleteIfExists(p.toPath()); tmp.delete(); } catch (Exception ignored) { } }
    }
    /** Igual que decode, pero lee directo del archivo (mp3, wav, mp4...): ffmpeg toma solo la pista de audio. */
    static float[] decodeFile(File f) throws Exception {
        File ff = U.which("ffmpeg");
        if (ff == null) throw new IOException("No se encontro ffmpeg en el PATH (instalalo con: winget install Gyan.FFmpeg).");
        return toFloats(U.runFfmpeg(List.of(ff.getPath(), "-loglevel", "error", "-i", f.getPath(), "-vn", "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "2", "-ar", String.valueOf(U.SR), "pipe:1"), null));
    }
    static float[] toFloats(byte[] b) { float[] f = new float[b.length / 8 * 2]; ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f); return f; }
    static byte[] toBytes(float[] f) { ByteBuffer bb = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN); bb.asFloatBuffer().put(f); return bb.array(); }
    /** Cambia la velocidad SIN cambiar el tono (filtro atempo). rate>1 = mas rapido. */
    static float[] stretch(float[] arr, double rate) throws Exception {
        File ff = U.which("ffmpeg"); if (ff == null) throw new IOException("ffmpeg no esta en el PATH");
        List<String> chain = new ArrayList<>(); double r = rate;
        while (r < .5) { chain.add("atempo=0.5"); r /= .5; }
        while (r > 2) { chain.add("atempo=2.0"); r /= 2; }
        chain.add(String.format(Locale.ROOT, "atempo=%.6f", r));
        return toFloats(U.runFfmpeg(List.of(ff.getPath(), "-loglevel", "error", "-f", "f32le", "-ar", String.valueOf(U.SR), "-ac", "2", "-i", "pipe:0", "-filter:a", String.join(",", chain), "-f", "f32le", "-ac", "2", "pipe:1"), toBytes(arr)));
    }
}
