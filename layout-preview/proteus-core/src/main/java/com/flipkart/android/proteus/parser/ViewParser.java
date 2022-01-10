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

package com.flipkart.android.proteus.parser;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.DrawableResourceProcessor;
import com.flipkart.android.proteus.processor.EventProcessor;
import com.flipkart.android.proteus.processor.GravityAttributeProcessor;
import com.flipkart.android.proteus.processor.ShapeAppearanceProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.processor.StyleResourceProcessor;
import com.flipkart.android.proteus.processor.TweenAnimationResourceProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.flipkart.android.proteus.view.ProteusAndroidView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.shape.Shapeable;

import java.util.Map;

/**
 * @author kiran.kumar
 */
public class ViewParser<V extends View> extends ViewTypeParser<V> {

  private static final String TAG = "ViewParser";

  private static final String ID_STRING_START_PATTERN = "@+id/";
  private static final String ID_STRING_START_PATTERN1 = "@id/";
  private static final String ID_STRING_NORMALIZED_PATTERN = ":id/";

  @NonNull
  @Override
  public String getType() {
    return "android.view.View";
  }

  @Nullable
  @Override
  public String getParentType() {
    return null;
  }

  @NonNull
  @Override
  public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data,
                                @Nullable ViewGroup parent, int dataIndex) {
    return new ProteusAndroidView(context);
  }

  @Override
  protected void addAttributeProcessors() {

    addLayoutParamsAttributeProcessor(Attributes.View.Width, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
          layoutParams.width = (int) dimension;
          view.setLayoutParams(layoutParams);
        }
      }
    });

    addLayoutParamsAttributeProcessor(Attributes.View.Height, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
          layoutParams.height = (int) dimension;
          view.setLayoutParams(layoutParams);
        }
      }
    });

    addAttributeProcessor("android:outlineProvider", new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        switch (value) {
          case "bounds":
            view.setOutlineProvider(ViewOutlineProvider.BOUNDS);
            break;
          case "paddedBounds":
            view.setOutlineProvider(ViewOutlineProvider.PADDED_BOUNDS);
            break;
          case "background":
            view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            break;
        }
      }
    });
    addAttributeProcessor(Attributes.View.Activated, new BooleanAttributeProcessor<V>() {
      @Override
      public void setBoolean(V view, boolean value) {
        view.setActivated(value);
      }
    });

    addAttributeProcessor(Attributes.View.OnClick, new EventProcessor<V>() {
      @Override
      public void setOnEventListener(final V view, final Value value) {
        view.setOnClickListener(v -> trigger(Attributes.View.OnClick, value, (ProteusView) view));
      }
    });

    addAttributeProcessor(Attributes.View.OnLongClick, new EventProcessor<V>() {
      @Override
      public void setOnEventListener(final V view, final Value value) {
        view.setOnLongClickListener(v -> {
          trigger(Attributes.View.OnLongClick, value, (ProteusView) view);
          return true;
        });
      }
    });

    addAttributeProcessor(Attributes.View.OnTouch, new EventProcessor<V>() {
      @SuppressLint("ClickableViewAccessibility")
      @Override
      public void setOnEventListener(final V view, final Value value) {
        view.setOnTouchListener((v, event) -> {
          trigger(Attributes.View.OnTouch, value, (ProteusView) view);
          return true;
        });
      }
    });

    addAttributeProcessor(Attributes.View.Background, new DrawableResourceProcessor<V>() {
      @Override
      public void setDrawable(V view, Drawable drawable) {
        view.setBackground(drawable);
      }

      @Override
      public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
        ProteusView proteusView = ((ProteusView) view);
        Value value =
                proteusView.getViewManager().getContext().obtainStyledAttribute(parent, view, attribute.getName());
        if (value == null) {
          value = attribute;
        }
        Drawable evaluate = DrawableResourceProcessor.evaluate(value, view);
        setDrawable(view, evaluate);
      }
    });

    addAttributeProcessor(Attributes.View.LayoutGravity, new GravityAttributeProcessor<V>() {
      @Override
      public void setGravity(V view, @Gravity int gravity) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();

        if (layoutParams instanceof LinearLayout.LayoutParams) {
          LinearLayout.LayoutParams linearLayoutParams = (LinearLayout.LayoutParams) layoutParams;
          linearLayoutParams.gravity = gravity;
          view.setLayoutParams(layoutParams);
        } else if (layoutParams instanceof FrameLayout.LayoutParams) {
          FrameLayout.LayoutParams linearLayoutParams = (FrameLayout.LayoutParams) layoutParams;
          linearLayoutParams.gravity = gravity;
          view.setLayoutParams(layoutParams);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e(TAG, "'layout_gravity' is only supported for LinearLayout and FrameLayout");
          }
        }
      }
    });

    addAttributeProcessor(Attributes.View.Padding, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        view.setPadding((int) dimension, (int) dimension, (int) dimension, (int) dimension);
      }
    });

    addAttributeProcessor(Attributes.View.PaddingLeft, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        view.setPadding((int) dimension, view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
      }
    });

    addAttributeProcessor(Attributes.View.PaddingTop, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        view.setPadding(view.getPaddingLeft(), (int) dimension, view.getPaddingRight(), view.getPaddingBottom());
      }
    });

    addAttributeProcessor(Attributes.View.PaddingRight, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), (int) dimension, view.getPaddingBottom());
      }
    });

    addAttributeProcessor(Attributes.View.PaddingBottom, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), (int) dimension);
      }
    });

    addAttributeProcessor(Attributes.View.Margin, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          ViewGroup.MarginLayoutParams layoutParams;
          layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
          layoutParams.setMargins((int) dimension, (int) dimension, (int) dimension, (int) dimension);
          view.setLayoutParams(layoutParams);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e(TAG, "margins can only be applied to views with parent ViewGroup");
          }
        }
      }
    });

    addAttributeProcessor(Attributes.View.MarginLeft, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          MarginHelper.setMarginLeft(view, (int) dimension);
        }
      }
    });

    addAttributeProcessor("android:layout_marginStart", new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          MarginHelper.setMarginLeft(view, (int) dimension);
        }
      }
    });

    addAttributeProcessor(Attributes.View.MarginTop, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          ViewGroup.MarginLayoutParams layoutParams;
          layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
          layoutParams.setMargins(layoutParams.leftMargin, (int) dimension, layoutParams.rightMargin, layoutParams.bottomMargin);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e(TAG, "margins can only be applied to views with parent ViewGroup");
          }
        }
      }
    });

    addAttributeProcessor(Attributes.View.MarginRight, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          MarginHelper.setMarginRight(view, (int) dimension);
        }
      }
    });

    addAttributeProcessor("android:layout_marginEnd", new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          MarginHelper.setMarginRight(view, (int) dimension);
        }
      }
    });

    addAttributeProcessor(Attributes.View.MarginBottom, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          MarginHelper.setMarginBottom(view, (int) dimension);
        }
      }
    });

    addAttributeProcessor(Attributes.View.MinHeight, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        view.setMinimumHeight((int) dimension);
      }
    });

    addAttributeProcessor(Attributes.View.MinWidth, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        view.setMinimumWidth((int) dimension);
      }
    });

    addAttributeProcessor(Attributes.View.Elevation, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        ViewCompat.setElevation(view, dimension);
      }
    });

    addAttributeProcessor(Attributes.View.Alpha, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        view.setAlpha(ParseHelper.parseFloat(value));
      }
    });

    addAttributeProcessor(Attributes.View.Visibility, new AttributeProcessor<V>() {
      @Override
      public void handleValue(View parent, V view, Value value) {
        if (value.isPrimitive() && value.getAsPrimitive().isNumber()) {
          view.setVisibility(value.getAsInt());
        } else {
          process((View) view.getParent(), view, precompile(value,  ProteusHelper.getProteusContext(view), ProteusHelper.getProteusContext(view).getFunctionManager()));
        }
      }

      @Override
      public void handleResource(View parent, V view, Resource resource) {
        Integer visibility = resource.getInteger(ProteusHelper.getProteusContext(view));
        view.setVisibility(null != visibility ? visibility : View.GONE);
      }

      @Override
      public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
        TypedArray a = attribute.apply(ProteusHelper.getProteusContext(view));
        view.setVisibility(a.getInt(0, View.GONE));
      }

      @Override
      public void handleStyle(View parent, V view, Style style) {
        Value gone = style.getValue(Attributes.View.Visibility, new Primitive("gone"));
        if (view instanceof ProteusView) {
          ((ProteusView) view).getViewManager().updateAttribute(Attributes.View.Visibility, gone.toString());
        }
      }

      @Override
      public Value compile(@Nullable Value value, ProteusContext context) {
        int visibility = ParseHelper.parseVisibility(value);
        return ParseHelper.getVisibility(visibility);
      }
    });

    addAttributeProcessor(Attributes.View.Id, new StringAttributeProcessor<V>() {
      @Override
      public void setString(final V view, String value) {
        ProteusContext context = ProteusHelper.getProteusContext(view);

          view.setId(context
                  .getInflater()
                  .getUniqueViewId(ParseHelper.parseViewId(value)));

        // set view id resource name
        final String resourceName = value;
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            String normalizedResourceName;
            if (!TextUtils.isEmpty(resourceName)) {
              String id;
              if (resourceName.startsWith(ID_STRING_START_PATTERN)) {
                id = resourceName.substring(ID_STRING_START_PATTERN.length());
              } else if (resourceName.startsWith(ID_STRING_START_PATTERN1)) {
                id = resourceName.substring(ID_STRING_START_PATTERN1.length());
              } else {
                id = resourceName;
              }
              normalizedResourceName = view.getContext().getPackageName() + ID_STRING_NORMALIZED_PATTERN + id;
            } else {
              normalizedResourceName = "";
            }
            info.setViewIdResourceName(normalizedResourceName);
          }
        });
      }
    });

    addAttributeProcessor(Attributes.View.ContentDescription, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        view.setContentDescription(value);
      }
    });

    addAttributeProcessor(Attributes.View.Clickable, new BooleanAttributeProcessor<V>() {
      @Override
      public void setBoolean(V view, boolean value) {
        view.setClickable(value);
      }
    });

    addAttributeProcessor(Attributes.View.Tag, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        view.setTag(value);
      }
    });

    addAttributeProcessor(Attributes.View.Enabled, new BooleanAttributeProcessor<V>() {
      @Override
      public void setBoolean(V view, boolean value) {
        view.setEnabled(value);
      }
    });

    addAttributeProcessor(Attributes.View.Selected, new BooleanAttributeProcessor<V>() {
      @Override
      public void setBoolean(V view, boolean value) {
        view.setSelected(value);
      }
    });

    addAttributeProcessor(Attributes.View.TransitionName, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        view.setTransitionName(value);
      }
    });

    addAttributeProcessor(Attributes.View.RequiresFadingEdge, new StringAttributeProcessor<V>() {

      private final String NONE = "none";
      private final String BOTH = "both";
      private final String VERTICAL = "vertical";
      private final String HORIZONTAL = "horizontal";

      @Override
      public void setString(V view, String value) {

        switch (value) {
          case BOTH:
            view.setVerticalFadingEdgeEnabled(true);
            view.setHorizontalFadingEdgeEnabled(true);
            break;
          case VERTICAL:
            view.setVerticalFadingEdgeEnabled(true);
            view.setHorizontalFadingEdgeEnabled(false);
            break;
          case HORIZONTAL:
            view.setVerticalFadingEdgeEnabled(false);
            view.setHorizontalFadingEdgeEnabled(true);
            break;
          case NONE:
          default:
            view.setVerticalFadingEdgeEnabled(false);
            view.setHorizontalFadingEdgeEnabled(false);
            break;
        }
      }
    });

    addAttributeProcessor(Attributes.View.FadingEdgeLength, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        view.setFadingEdgeLength(ParseHelper.parseInt(value));
      }
    });

    addAttributeProcessor(Attributes.View.Animation, new TweenAnimationResourceProcessor<V>() {

      @Override
      public void setAnimation(V view, Animation animation) {
        view.setAnimation(animation);
      }
    });

    addAttributeProcessor(Attributes.View.TextAlignment, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {

        Integer textAlignment = ParseHelper.parseTextAlignment(value);
        if (null != textAlignment) {
          //noinspection ResourceType
          view.setTextAlignment(textAlignment);
        }
      }

    });


    // AppCompat Attributes
    addAttributeProcessor("app:backgroundTint", new ColorResourceProcessor<V>() {
      @Override
      public void setColor(V view, int color) {
        ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(color));
      }

      @Override
      public void setColor(V view, ColorStateList colors) {
        ViewCompat.setBackgroundTintList(view, colors);
      }
    });

    addAttributeProcessor(Attributes.View.Style, new StyleResourceProcessor<V>() {
      @Override
      public void handleStyle(View parent, View view, Style style) {
        style.applyStyle(parent, (ProteusView) view, false);
      }
    });

    addAttributeProcessor("android:theme", new StyleResourceProcessor<V>() {
      @Override
      public void handleStyle(View parent, View view, Style style) {
        ProteusView proteusView = (ProteusView) view;
        ProteusView.Manager viewManager = proteusView.getViewManager();

        // if the view has already a theme set, then its most likely that this is
        // a theme overlay, we just override the values from the existing theme
        Style currentTheme = viewManager.getTheme();
        Style currentStyle = viewManager.getStyle();
        if (currentTheme != null) {
          currentTheme = currentTheme.copy().getAsStyle();
          for (Map.Entry<String, Value> entry : style.getValues().entrySet()) {
            currentTheme.addValue(entry.getKey(), entry.getValue());
            if (currentStyle != null) {
              currentStyle.addValue(entry.getKey(), entry.getValue());
            }
          }

          currentTheme.applyTheme(parent, (ProteusView) view,true);

          if (viewManager.getStyle() != null) {
             viewManager.getStyle().applyStyle(parent, (ProteusView) view, false);
          }
        } else {
          style.applyTheme(parent, (ProteusView) view, true);
        }
      }
    });
    addAttributeProcessor("materialThemeOverlay",  new StyleResourceProcessor<V>() {
      @Override
      public void handleStyle(View parent, View view, Style style) {
        ProteusView proteusView = (ProteusView) view;
        ProteusView.Manager viewManager = proteusView.getViewManager();
//        // wait for the other attributes to be applied
////        view.post(() -> {
          if (viewManager.getTheme() != null) {
            Style currentTheme = viewManager.getTheme().copy().getAsStyle();
            currentTheme.getValues().remove("materialThemeOverlay");

            for (Map.Entry<String, Value> entry : style.getValues().entrySet()) {
                currentTheme.addValue(entry.getKey(), entry.getValue());
            }

            currentTheme.applyTheme(parent, (ProteusView) view, false);
          } else {
            style.applyTheme(parent, (ProteusView) view, false);
          }
//        });
      }
    });

//    addAttributeProcessor("app:elevation", new DimensionAttributeProcessor<V>() {
//      @Override
//      public void setDimension(V view, float dimension) {
//        ViewCompat.setElevation(view, dimension);
//      }
//    });

    addAttributeProcessor("app:shapeAppearance", new ShapeAppearanceProcessor<V>() {

      @Override
      public void setShapeAppearance(View view, ShapeAppearanceModel model) {
        if (view instanceof Shapeable) {
          ((Shapeable) view).setShapeAppearanceModel(model);
        }
      }
    });

    addAttributeProcessor("app:shapeAppearanceOverlay", new ShapeAppearanceProcessor<V>() {

      @Override
      public void setShapeAppearance(View view, ShapeAppearanceModel model) {
        if (view instanceof Shapeable) {
          ((Shapeable) view).setShapeAppearanceModel(model);
        }
      }
    });
  }

  @Override
  public boolean handleChildren(V view, Value children) {
    return false;
  }

  @Override
  public boolean addView(ProteusView parent, ProteusView view) {
    return false;
  }
}
