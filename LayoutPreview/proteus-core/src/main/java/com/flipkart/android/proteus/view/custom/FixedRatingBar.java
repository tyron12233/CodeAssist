/*
 * Copyright 2019 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.android.proteus.view.custom;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.graphics.drawable.shapes.Shape;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.RatingBar;

/**
 * Rating bar code is full of bugs. For width of rating bar to be set correctly, sample tile width should be set
 * But since tilefy method is private and we are doing tile-fication ourselves, sample tile should be maintained by us
 * Created by kirankumar on 04/12/14.
 */
public class FixedRatingBar extends RatingBar {
  private Bitmap sampleTile;

  public FixedRatingBar(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public FixedRatingBar(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
  }

  public FixedRatingBar(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public FixedRatingBar(Context context) {
    super(context);
  }

  public void setSampleTile(Bitmap bitmap) {
    this.sampleTile = bitmap;
  }

  protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    if (sampleTile != null) {
      final int width = sampleTile.getWidth() * getNumStars();
      setMeasuredDimension(resolveSize(width, widthMeasureSpec), getMeasuredHeight());
    }
  }

  Shape getDrawableShape() {
    final float[] roundedCorners = new float[]{5, 5, 5, 5, 5, 5, 5, 5};
    return new RoundRectShape(roundedCorners, null, null);
  }

  /**
   * Taken from AOSP !!
   * Converts a drawable to a tiled version of itself. It will recursively
   * traverse layer and state list drawables.
   */
  public Drawable getTiledDrawable(Drawable drawable, boolean clip) {

    if (drawable instanceof LayerDrawable) {
      LayerDrawable background = (LayerDrawable) drawable;
      final int N = background.getNumberOfLayers();
      Drawable[] outDrawables = new Drawable[N];

      for (int i = 0; i < N; i++) {
        int id = background.getId(i);
        outDrawables[i] = getTiledDrawable(background.getDrawable(i),
          (id == android.R.id.progress || id == android.R.id.secondaryProgress));
      }

      LayerDrawable newBg = new LayerDrawable(outDrawables);

      for (int i = 0; i < N; i++) {
        newBg.setId(i, background.getId(i));
      }

      return newBg;

    } else if (drawable instanceof BitmapDrawable) {

      final Bitmap tileBitmap = ((BitmapDrawable) drawable).getBitmap();
      if (sampleTile == null) {
        sampleTile = tileBitmap;
      }
      final ShapeDrawable shapeDrawable = new ShapeDrawable(getDrawableShape());

      final BitmapShader bitmapShader = new BitmapShader(tileBitmap,
        Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
      shapeDrawable.getPaint().setShader(bitmapShader);

      return (clip) ? new ClipDrawable(shapeDrawable, Gravity.LEFT,
        ClipDrawable.HORIZONTAL) : shapeDrawable;
    }

    return drawable;
  }
}
