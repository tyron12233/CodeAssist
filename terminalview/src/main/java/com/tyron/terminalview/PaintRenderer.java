package com.tyron.terminalview;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.FloatMath;


class PaintRenderer extends BaseTextRenderer {
    public PaintRenderer(int fontSize, ColorScheme scheme) {
        super(scheme);
        mTextPaint = new Paint();
        mTextPaint.setTypeface(Typeface.MONOSPACE);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(fontSize);

        mCharHeight = (int) Math.ceil(mTextPaint.getFontSpacing());
        mCharAscent = (int) Math.ceil(mTextPaint.ascent());
        mCharDescent = mCharHeight + mCharAscent;
        mCharWidth = mTextPaint.measureText(EXAMPLE_CHAR, 0, 1);
    }

    public void drawTextRun(Canvas canvas, float x, float y, int lineOffset,
            int runWidth, char[] text, int index, int count,
            boolean selectionStyle, int textStyle,
            int cursorOffset, int cursorIndex, int cursorIncr, int cursorWidth, int cursorMode) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        int effect = TextStyle.decodeEffect(textStyle);

        boolean inverse =  mReverseVideo ^
                (effect & (TextStyle.fxInverse | TextStyle.fxItalic)) != 0;
        if (inverse) {
            int temp = foreColor;
            foreColor = backColor;
            backColor = temp;
        }

        if (selectionStyle) {
            backColor = TextStyle.ciCursorBackground;
        }

        boolean blink = (effect & TextStyle.fxBlink) != 0;
        if (blink && backColor < 8) {
            backColor += 8;
        }
        mTextPaint.setColor(mPalette[backColor]);

        float left = x + lineOffset * mCharWidth;
        canvas.drawRect(left, y + mCharAscent - mCharDescent,
                left + runWidth * mCharWidth, y,
                mTextPaint);

        boolean cursorVisible = lineOffset <= cursorOffset && cursorOffset < (lineOffset + runWidth);
        float cursorX = 0;
        if (cursorVisible) {
            cursorX = x + cursorOffset * mCharWidth;
            drawCursorImp(canvas, (int) cursorX, y, cursorWidth * mCharWidth, mCharHeight, cursorMode);
        }

        boolean invisible = (effect & TextStyle.fxInvisible) != 0;
        if (!invisible) {
            boolean bold = (effect & TextStyle.fxBold) != 0;
            boolean underline = (effect & TextStyle.fxUnderline) != 0;
            if (bold) {
                mTextPaint.setFakeBoldText(true);
            }
            if (underline) {
                mTextPaint.setUnderlineText(true);
            }
            int textPaintColor;
            if (foreColor < 8 && bold) {
                // In 16-color mode, bold also implies bright foreground colors
                textPaintColor = mPalette[foreColor+8];
            } else {
                textPaintColor = mPalette[foreColor];
            }
            mTextPaint.setColor(textPaintColor);

            float textOriginY = y - mCharDescent;

            if (cursorVisible) {
                // Text before cursor
                int countBeforeCursor = cursorIndex - index;
                int countAfterCursor = count - (countBeforeCursor + cursorIncr);
                if (countBeforeCursor > 0){
                    canvas.drawText(text, index, countBeforeCursor, left, textOriginY, mTextPaint);
                }
                // Text at cursor
                mTextPaint.setColor(mPalette[TextStyle.ciCursorForeground]);
                canvas.drawText(text, cursorIndex, cursorIncr, cursorX,
                        textOriginY, mTextPaint);
                // Text after cursor
                if (countAfterCursor > 0) {
                    mTextPaint.setColor(textPaintColor);
                    canvas.drawText(text, cursorIndex + cursorIncr, countAfterCursor,
                            cursorX + cursorWidth * mCharWidth,
                            textOriginY, mTextPaint);
                }
            } else {
                canvas.drawText(text, index, count, left, textOriginY, mTextPaint);
            }
            if (bold) {
                mTextPaint.setFakeBoldText(false);
            }
            if (underline) {
                mTextPaint.setUnderlineText(false);
            }
        }
    }

    public int getCharacterHeight() {
        return mCharHeight;
    }

    public float getCharacterWidth() {
        return mCharWidth;
    }

    public int getTopMargin() {
        return mCharDescent;
    }

    private final Paint mTextPaint;
    private final float mCharWidth;
    private final int mCharHeight;
    private final int mCharAscent;
    private final int mCharDescent;
    private static final char[] EXAMPLE_CHAR = {'X'};
}