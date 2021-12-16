package com.tyron.layoutpreview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.view.UnknownViewGroup;

/**
 * FrameLayout subclass that draws the boundaries of all of its child views.
 */
public class BoundaryDrawingFrameLayout extends FrameLayout {

    private final Paint mPaint;

    public BoundaryDrawingFrameLayout(@NonNull Context context) {
        this(context, null);
    }

    public BoundaryDrawingFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BoundaryDrawingFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BoundaryDrawingFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        setWillNotDraw(false);

        mPaint = new Paint();
        mPaint.setColor(Color.GRAY);
        mPaint.setStyle(Paint.Style.STROKE);
       // mPaint.setPathEffect(new DashPathEffect(new float[]{10f, 10f}, 0f));
        mPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        drawViewGroup(this, canvas);
    }

    private void drawViewGroup(ViewGroup viewGroup, Canvas canvas) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            drawView(child, canvas);
            if (child instanceof ViewGroup && !(child instanceof UnknownViewGroup)) {
                drawViewGroup((ViewGroup) child, canvas);
            }
        };
    }

    private final Rect mTempRect = new Rect();

    private void drawView(View view, Canvas canvas) {
        view.getDrawingRect(mTempRect);
        this.offsetDescendantRectToMyCoords(view, mTempRect);
        canvas.drawLine(mTempRect.left, mTempRect.top, mTempRect.right, mTempRect.top, mPaint);
        canvas.drawLine(mTempRect.right, mTempRect.top, mTempRect.right, mTempRect.bottom, mPaint);
        canvas.drawLine(mTempRect.right, mTempRect.bottom, mTempRect.left, mTempRect.bottom, mPaint);
        canvas.drawLine(mTempRect.left, mTempRect.bottom, mTempRect.left, mTempRect.top, mPaint);
    }
}
