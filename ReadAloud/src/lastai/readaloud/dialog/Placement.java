package lastai.readaloud.dialog;

/** Where the speaker button goes. Pure math in screen coordinates (y up), unit tested. */
public final class Placement {
    public static final float PAD = 4f;

    private Placement() {}

    /**
     * @param panel  text panel rect: x, y (bottom), w, h
     * @param last   last paragraph of the latest portion: x, y (bottom), w, h; or null
     * @param size   button size
     * @param corner true to always use the corner
     * @return {x, y} of the button's bottom-left corner
     */
    public static float[] place(float[] panel, float[] last, float size, boolean corner) {
        float px = panel[0], py = panel[1], pw = panel[2], ph = panel[3];
        float cornerX = px + pw - size - PAD - 12f; // leave room for the scrollbar
        float cornerY = py + PAD;
        if (corner || last == null) return new float[]{cornerX, cornerY};
        float x = last[0];
        float y = last[1] - size - PAD;
        boolean inside = y >= py + PAD && last[1] <= py + ph && x >= px - 1 && x + size <= px + pw;
        if (!inside) return new float[]{cornerX, cornerY};
        return new float[]{x, y};
    }
}
