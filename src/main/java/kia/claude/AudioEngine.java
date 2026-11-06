package kia.claude;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/** Reloj de audio. Posicion = tiempo de la pista que se esta ESCUCHANDO (usa las muestras que ya salieron por la tarjeta). */
final class AudioEngine {
    float[] data; volatile float[] buf; double dur = 0, rate = 1.0; volatile boolean playing = false; volatile float vol = 1f; volatile boolean muted = false; private double pos0 = 0;
    private SourceDataLine line; boolean clockOnly = false; String error = "";
    private volatile int gen = 0; private volatile int startFrame = 0; private volatile long baseFrames = 0; private long t0nano = 0; private double p0 = 0;

    void load(float[] arr) { stop(); data = arr; buf = arr; rate = 1.0; dur = arr.length / 2.0 / U.SR; pos0 = 0; }
    private void open() {
        if (clockOnly || line != null) return;
        try {
            AudioFormat fmt = new AudioFormat(U.SR, 16, 2, true, false); SourceDataLine l = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            l.open(fmt, (int) (U.SR * 0.15) * 4); line = l;
        } catch (Throwable e) { error = String.valueOf(e); clockOnly = true; line = null; }
    }
    double pos() {
        if (!playing) return pos0;
        double p;
        if (clockOnly || line == null) p = p0 + (System.nanoTime() - t0nano) / 1e9 * rate;
        else p = Math.max(p0, (startFrame + (line.getLongFramePosition() - baseFrames)) / (double) U.SR * rate);
        return Math.min(dur, p);
    }
    private void startThread() {
        final int my = ++gen; final SourceDataLine l = line;
        if (l == null || buf == null) return;
        Thread t = new Thread(() -> {
            final int CH = 2048; byte[] out = new byte[CH * 4]; int idx = startFrame; float[] b = buf;
            while (my == gen && playing) {
                // swap(): otra pista mientras suena; se sigue en la misma muestra con una mezcla corta (sin corte)
                float[] cur = buf, viejo = null;
                if (cur != null && cur != b) { viejo = b; b = cur; }
                int n = b.length / 2;
                if (idx >= n) break;
                int k = Math.min(CH, n - idx);
                for (int i = 0; i < k; i++) {
                    float w = viejo == null ? 0f : 1f - (float) i / k;
                    for (int c = 0; c < 2; c++) {
                        int j = (idx + i) * 2 + c;
                        float m = b[j];
                        if (viejo != null) m = m * (1f - w) + (j < viejo.length ? viejo[j] : 0f) * w;
                        float v = m * (muted ? 0f : vol); if (v > 1f) v = 1f; else if (v < -1f) v = -1f; int s = (int) (v * 32767); out[i * 4 + c * 2] = (byte) s; out[i * 4 + c * 2 + 1] = (byte) (s >> 8);
                    }
                }
                l.write(out, 0, k * 4); idx += k;
            }
        }, "audio-out"); t.setDaemon(true); t.start();
    }
    private void begin(double at) {
        p0 = at; t0nano = System.nanoTime(); startFrame = (int) (at / rate * U.SR);
        if (!clockOnly && line != null) { gen++; line.stop(); line.flush(); baseFrames = line.getLongFramePosition(); line.start(); }
        playing = true; if (!clockOnly && line != null) startThread();
    }
    void play() {
        if (buf == null || playing) return;
        if (pos0 >= dur - .01) pos0 = 0; open(); begin(pos0);
    }
    void pause() {
        if (!playing) return;
        pos0 = pos(); playing = false; gen++; if (line != null) { line.stop(); line.flush(); }
    }
    void stop() { pause(); }
    /**
     * Cambia el audio por otro (otra pista de la misma cancion) sin detenerlo: si esta sonando sigue en la misma
     * posicion y el hilo de salida mezcla un instante las dos para que no se oiga el corte.
     */
    void swap(float[] arr) {
        if (!playing || rate != 1.0 || clockOnly || line == null) {
            double p = pos(); boolean was = playing; load(arr); pos0 = Math.min(p, dur); if (was) play(); return;
        }
        data = arr; dur = arr.length / 2.0 / U.SR; buf = arr;
    }
    void seek(double t) { t = U.clamp(t, 0, dur); if (playing) begin(t); else pos0 = t; }
    /** arr: audio ya estirado para esa velocidad (null si rate == 1). */
    void applyRate(double r, float[] arr) {
        double p = pos(); boolean was = playing; if (was) { pos0 = p; playing = false; gen++; if (line != null) { line.stop(); line.flush(); } }
        rate = r; buf = (r == 1 || arr == null) ? data : arr; pos0 = p; if (was) play();
    }
    boolean ended() { return playing && pos() >= dur - .0005; }
    /** minimo y maximo por bloque de 512 muestras (para dibujar la onda). */
    float[][] peaks(int block) {
        if (data == null) return null; int n = data.length / 2 / block; float[][] r = {new float[n], new float[n]};
        for (int i = 0; i < n; i++) {
            float mn = 1e9f, mx = -1e9f;
            for (int j = i * block; j < (i + 1) * block; j++) { float m = (data[2 * j] + data[2 * j + 1]) * .5f; if (m < mn) mn = m; if (m > mx) mx = m; }
            r[0][i] = mn; r[1][i] = mx;
        }
        return r;
    }
    void close() { pause(); if (line != null) { line.close(); line = null; } data = buf = null; }
}
