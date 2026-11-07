package lastai.readaloud.test;

import lastai.readaloud.dialog.Placement;

public final class PlacementTests {
    static final float[] PANEL = {100, 200, 500, 400}; // x 100..600, y 200..600

    public static void belowTheLastParagraphWhenThereIsRoom() {
        float[] at = Placement.place(PANEL, new float[]{110, 450, 480, 40}, 22, false);
        T.eq(110f, at[0], "x = paragraph left");
        T.eq(450f - 22 - Placement.PAD, at[1], "y below paragraph");
    }

    public static void cornerWhenTheTextFillsThePanel() {
        float[] at = Placement.place(PANEL, new float[]{110, 205, 480, 40}, 22, false);
        T.eq(600f - 22 - Placement.PAD - 12, at[0], "corner x");
        T.eq(200f + Placement.PAD, at[1], "corner y");
    }

    public static void cornerWhenScrolledAway() {
        float[] at = Placement.place(PANEL, new float[]{110, 700, 480, 40}, 22, false);
        T.eq(200f + Placement.PAD, at[1], "last paragraph above the visible area");
    }

    public static void cornerWhenAskedOrNoText() {
        float[] a = Placement.place(PANEL, new float[]{110, 450, 480, 40}, 22, true);
        float[] b = Placement.place(PANEL, null, 22, false);
        T.eq(a[0], b[0], "same x");
        T.eq(a[1], b[1], "same y");
    }
}
