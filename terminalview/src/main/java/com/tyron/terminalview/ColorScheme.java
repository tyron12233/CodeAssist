package com.tyron.terminalview;

/**
 * A class describing a color scheme for an {@link EmulatorView}.
 * <p>
 * <code>EmulatorView</code> supports changing its default foreground,
 * background, and cursor colors.  Passing a <code>ColorScheme</code> to
 * {@link EmulatorView#setColorScheme setColorScheme} will cause the
 * <code>EmulatorView</code> to use the specified colors as its defaults.
 * <p>
 * Cursor colors can be omitted when specifying a color scheme; if no cursor
 * colors are specified, <code>ColorScheme</code> will automatically select
 * suitable cursor colors for you.
 *
 * @see EmulatorView#setColorScheme
 */

public class ColorScheme {
    private final int foreColor;
    private final int backColor;
    private int cursorForeColor;
    private int cursorBackColor;
    final private static int sDefaultCursorBackColor = 0xff808080;

    private void setDefaultCursorColors() {
        cursorBackColor = sDefaultCursorBackColor;
        // Use the foreColor unless the foreColor is too similar to the cursorBackColor
        int foreDistance = distance(foreColor, cursorBackColor);
        int backDistance = distance(backColor, cursorBackColor);
        if (foreDistance * 2 >= backDistance) {
            cursorForeColor = foreColor;
        } else {
            cursorForeColor = backColor;
        }
    }

    private static int distance(int a, int b) {
        return channelDistance(a, b, 0) * 3 +
               channelDistance(a, b, 1) * 5 +
               channelDistance(a, b, 2);
    }

    private static int channelDistance(int a, int b, int channel) {
        return Math.abs(getChannel(a, channel) - getChannel(b, channel));
    }

    private static int getChannel(int color, int channel) {
        return 0xff & (color >> ((2 - channel) * 8));
    }

    /**
     * Creates a <code>ColorScheme</code> object.
     *
     * @param foreColor The foreground color as an ARGB hex value.
     * @param backColor The background color as an ARGB hex value.
     */
    public ColorScheme(int foreColor, int backColor) {
        this.foreColor = foreColor;
        this.backColor = backColor;
        setDefaultCursorColors();
    }

    /**
     * Creates a <code>ColorScheme</code> object.
     *
     * @param foreColor       The foreground color as an ARGB hex value.
     * @param backColor       The background color as an ARGB hex value.
     * @param cursorForeColor The cursor foreground color as an ARGB hex value.
     * @param cursorBackColor The cursor foreground color as an ARGB hex value.
     */
    public ColorScheme(int foreColor, int backColor, int cursorForeColor, int cursorBackColor) {
        this.foreColor = foreColor;
        this.backColor = backColor;
        this.cursorForeColor = cursorForeColor;
        this.cursorBackColor = cursorBackColor;
    }

    /**
     * Creates a <code>ColorScheme</code> object from an array.
     *
     * @param scheme An integer array <code>{ foreColor, backColor,
     *               optionalCursorForeColor, optionalCursorBackColor }</code>.
     */
    public ColorScheme(int[] scheme) {
        int schemeLength = scheme.length;
        if (schemeLength != 2 && schemeLength != 4) {
            throw new IllegalArgumentException();
        }
        this.foreColor = scheme[0];
        this.backColor = scheme[1];
        if (schemeLength == 2) {
            setDefaultCursorColors();
        } else {
            this.cursorForeColor = scheme[2];
            this.cursorBackColor = scheme[3];
        }
    }

    /**
     * @return This <code>ColorScheme</code>'s foreground color as an ARGB
     * hex value.
     */
    public int getForeColor() {
        return foreColor;
    }

    /**
     * @return This <code>ColorScheme</code>'s background color as an ARGB
     * hex value.
     */
    public int getBackColor() {
        return backColor;
    }

    /**
     * @return This <code>ColorScheme</code>'s cursor foreground color as an ARGB
     * hex value.
     */
    public int getCursorForeColor() {
        return cursorForeColor;
    }

    /**
     * @return This <code>ColorScheme</code>'s cursor background color as an ARGB
     * hex value.
     */
    public int getCursorBackColor() {
        return cursorBackColor;
    }
}