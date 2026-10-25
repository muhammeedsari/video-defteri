package app.sesdefteri.dsp;

/**
 * "Make the voice clearer": rumble filter, less mud (~300 Hz), more presence (~3 kHz), a little air (8 kHz shelf)
 * and a gentle exciter (soft harmonics from the 3 kHz+ band). Gains are chosen by the caller from the analysis.
 */
public final class Clarity extends Node {
    final int ch;
    final Biquad[] eq;
    final Biquad hp1, hp2;
    final double ex;
    float[] h = new float[0];

    public Clarity(int ch, double fs, double mudDb, double presDb, double airDb, double exciter) {
        this.ch = ch;
        java.util.ArrayList<Biquad> l = new java.util.ArrayList<>();
        l.add(Biquad.highpass(ch, fs, 80, 0.707));
        if (mudDb != 0) l.add(Biquad.peaking(ch, fs, 300, 0.9, mudDb));
        if (presDb != 0) l.add(Biquad.peaking(ch, fs, 3000, 0.8, presDb));
        if (airDb != 0) l.add(new Biquad(ch).setHighShelf(fs, 8000, 0.707, airDb));
        eq = l.toArray(new Biquad[0]);
        hp1 = Biquad.highpass(ch, fs, 3000, 0.707);
        hp2 = Biquad.highpass(ch, fs, 2500, 0.707);
        ex = exciter;
    }

    @Override public void push(float[][] b, int n) throws Exception {
        if (h.length < n) h = new float[n];
        for (int c = 0; c < ch; c++) {
            float[] x = b[c];
            for (Biquad q : eq) q.process(c, x, 0, n);
            if (ex > 0) {
                System.arraycopy(x, 0, h, 0, n);
                hp1.process(c, h, 0, n);
                for (int i = 0; i < n; i++) { float v = h[i]; h[i] = (float) Math.tanh(8 * v * Math.abs(v)); }
                hp2.process(c, h, 0, n);   // only the new harmonics, no DC or low end
                for (int i = 0; i < n; i++) x[i] += (float) (ex * h[i]);
            }
        }
        next.push(b, n);
    }
}
