package app.sesdefteri.dsp;

/**
 * Character voices: phone, radio, megaphone, robot. Band limiting + soft saturation (+ ring modulation and a metallic
 * comb for the robot). Higher / deeper voices are TimePitch without formant keeping, so they are not here.
 */
public final class VoiceFx extends Node {
    final int ch;
    final Biquad[] f;
    final double drive, norm, ring, makeup;
    final float[][] comb;
    final int combLen;
    final double combFb;
    int cp;
    long t;
    final double w;

    public VoiceFx(int ch, double fs, String preset) {
        this.ch = ch;
        switch (preset) {
            case "phone":
                f = new Biquad[] { Biquad.highpass(ch, fs, 320, 0.707), Biquad.highpass(ch, fs, 320, 0.707),
                    Biquad.lowpass(ch, fs, 3300, 0.707), Biquad.lowpass(ch, fs, 3300, 0.707), Biquad.peaking(ch, fs, 1800, 1, 3) };
                drive = 2.2; ring = 0; makeup = 4; break;
            case "radio":
                f = new Biquad[] { Biquad.highpass(ch, fs, 450, 0.707), Biquad.lowpass(ch, fs, 5000, 0.707), Biquad.lowpass(ch, fs, 5000, 0.707),
                    Biquad.peaking(ch, fs, 1500, 0.8, 4) };
                drive = 3; ring = 0; makeup = 3; break;
            case "megaphone":
                f = new Biquad[] { Biquad.highpass(ch, fs, 650, 0.707), Biquad.highpass(ch, fs, 650, 0.707), Biquad.lowpass(ch, fs, 3600, 1.2),
                    Biquad.peaking(ch, fs, 1900, 1.4, 8) };
                drive = 5; ring = 0; makeup = 1; break;
            default:   // robot
                f = new Biquad[] { Biquad.highpass(ch, fs, 120, 0.707) };
                drive = 1.6; ring = 55; makeup = 6; break;
        }
        norm = 1 / Math.tanh(drive);
        w = 2 * Math.PI * ring / fs;
        combLen = ring > 0 ? (int) Math.round(fs * 0.0066) : 0;
        combFb = 0.55;
        comb = new float[ch][Math.max(1, combLen)];
    }

    @Override public void push(float[][] b, int n) throws Exception {
        for (int c = 0; c < ch; c++) for (Biquad q : f) q.process(c, b[c], 0, n);
        double mk = Math.pow(10, makeup / 20);
        for (int i = 0; i < n; i++) {
            double rm = ring > 0 ? Math.sin(w * (t + i)) : 1;
            for (int c = 0; c < ch; c++) {
                double x = b[c][i];
                if (ring > 0) {
                    x = 0.85 * x * rm + 0.15 * x;
                    double y = x + combFb * comb[c][cp];
                    comb[c][cp] = (float) y;
                    x = y * 0.6;
                }
                b[c][i] = (float) (Math.tanh(drive * x) * norm * mk);
            }
            if (combLen > 0 && ++cp >= combLen) cp = 0;
        }
        t += n;
        next.push(b, n);
    }
}
