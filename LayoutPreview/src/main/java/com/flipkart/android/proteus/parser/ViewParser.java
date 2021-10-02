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
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.DrawableResourceProcessor;
import com.flipkart.android.proteus.processor.EventProcessor;
import com.flipkart.android.proteus.processor.GravityAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.processor.TweenAnimationResourceProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;
import com.flipkart.android.proteus.view.ProteusAndroidView;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    return "View";
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

    addAttributeProcessor(Attributes.View.Activated, new BooleanAttributeProcessor<V>() {
      @Override
      public void setBoolean(V view, boolean value) {
        view.setActivated(value);
      }
    });

    addAttributeProcessor(Attributes.View.OnClick, new EventProcessor<V>() {
      @Override
      public void setOnEventListener(final V view, final Value value) {
        view.setOnClickListener(new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            trigger(Attributes.View.OnClick, value, (ProteusView) view);
          }
        });
      }
    });

    addAttributeProcessor(Attributes.View.OnLongClick, new EventProcessor<V>() {
      @Override
      public void setOnEventListener(final V view, final Value value) {
        view.setOnLongClickListener(new View.OnLongClickListener() {
          @Override
          public boolean onLongClick(View v) {
            trigger(Attributes.View.OnLongClick, value, (ProteusView) view);
            return true;
          }
        });
      }
    });

    addAttributeProcessor(Attributes.View.OnTouch, new EventProcessor<V>() {
      @Override
      public void setOnEventListener(final V view, final Value value) {
        view.setOnTouchListener(new View.OnTouchListener() {
          @Override
          public boolean onTouch(View v, MotionEvent event) {
            trigger(Attributes.View.OnTouch, value, (ProteusView) view);
            return true;
          }
        });
      }
    });

    addAttributeProcessor(Attributes.View.Background, new DrawableResourceProcessor<V>() {
      @Override
      public void setDrawable(V view, Drawable drawable) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
          //noinspection deprecation
          view.setBackgroundDrawable(drawable);
        } else {
          view.setBackground(drawable);
        }
      }
    });

    addAttributeProcessor(Attributes.View.Height, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
          layoutParams.height = (int) dimension;
          view.setLayoutParams(layoutParams);
        }
      }
    });

    addAttributeProcessor(Attributes.View.Width, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
          layoutParams.width = (int) dimension;
          view.setLayoutParams(layoutParams);
        }
      }
    });
    addAttributeProcessor(Attributes.View.Weight, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        LinearLayout.LayoutParams layoutParams;
        if (view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
          layoutParams = (LinearLayout.LayoutParams) view.getLayoutParams();
          layoutParams.weight = ParseHelper.parseFloat(value);
          view.setLayoutParams(layoutParams);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e(TAG, "'weight' is only supported for LinearLayouts");
          }
        }
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
          ViewGroup.MarginLayoutParams layoutParams;
          layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
          layoutParams.setMargins((int) dimension, layoutParams.topMargin, layoutParams.rightMargin, layoutParams.bottomMargin);
          view.setLayoutParams(layoutParams);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e(TAG, "margins can only be applied to views with parent ViewGroup");
          }
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
          view.setLayoutParams(layoutParams);
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
          ViewGroup.MarginLayoutParams layoutParams;
          layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
          layoutParams.setMargins(layoutParams.leftMargin, layoutParams.topMargin, (int) dimension, layoutParams.bottomMargin);
          view.setLayoutParams(layoutParams);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e(TAG, "margins can only be applied to views with parent ViewGroup");
          }
        }
      }
    });

    addAttributeProcessor(Attributes.View.MarginBottom, new DimensionAttributeProcessor<V>() {
      @Override
      public void setDimension(V view, float dimension) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
          ViewGroup.MarginLayoutParams layoutParams;
          layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
          layoutParams.setMargins(layoutParams.leftMargin, layoutParams.topMargin, layoutParams.rightMargin, (int) dimension);
          view.setLayoutParams(layoutParams);
        } else {
          if (ProteusConstants.isLoggingEnabled()) {
            Log.e(TAG, "margins can only be applied to views with parent ViewGroup");
          }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          view.setElevation(dimension);
        }
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
      public void handleValue(V view, Value value) {
        if (value.isPrimitive() && value.getAsPrimitive().isNumber()) {
          // noinspection ResourceType
          view.setVisibility(value.getAsInt());
        } else {
          process(view, precompile(value, view.getContext(), ((ProteusContext) view.getContext()).getFunctionManager()));
        }
      }

      @Override
      public void handleResource(V view, Resource resource) {
        Integer visibility = resource.getInteger(view.getContext());
        //noinspection WrongConstant
        view.setVisibility(null != visibility ? visibility : View.GONE);
      }

      @Override
      public void handleAttributeResource(V view, AttributeResource attribute) {
        TypedArray a = attribute.apply(view.getContext());
        //noinspection WrongConstant
        view.setVisibility(a.getInt(0, View.GONE));
      }

      @Override
      public void handleStyleResource(V view, StyleResource style) {
        TypedArray a = style.apply(view.getContext());
        //noinspection WrongConstant
        view.setVisibility(a.getInt(0, View.GONE));
      }

      @Override
      public Value compile(@Nullable Value value, Context context) {
        int visibility = ParseHelper.parseVisibility(value);
        return ParseHelper.getVisibility(visibility);
      }
    });

    addAttributeProcessor(Attributes.View.Id, new StringAttributeProcessor<V>() {
      @Override
      public void setString(final V view, String value) {
        if (view instanceof ProteusView) {
          view.setId(((ProteusView) view).getViewManager().getContext().getInflater().getUniqueViewId(value));
        }

        // set view id resource name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
          final String resourceName = value;
          view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
              super.onInitializeAccessibilityNodeInfo(host, info);
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
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
            }
          });
        }
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

    addAttributeProcessor(Attributes.View.Style, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        ProteusView.Manager viewManager = ((ProteusView) view).getViewManager();
        ProteusContext context = viewManager.getContext();
        Layout layout = viewManager.getLayout();

        ViewTypeParser handler = context.getInflater().getParser(layout.type);

        String[] styleSet = value.split(ProteusConstants.STYLE_DELIMITER);
        for (String styleName : styleSet) {
          Map<String, Value> style = context.getStyle(styleName);
          if (null != style) {
            process(context.getStyle(styleName), (ProteusView) view, (handler != null ? handler : ViewParser.this));
          }
        }
      }

      private void process(Map<String, Value> style, ProteusView proteusView, ViewTypeParser handler) {
        for (Map.Entry<String, Value> entry : style.entrySet()) {
          //noinspection unchecked
          handler.handleAttribute(proteusView.getAsView(), handler.getAttributeId(entry.getKey()), entry.getValue());
        }
      }
    });

    addAttributeProcessor(Attributes.View.TransitionName, new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          view.setTransitionName(value);
        }
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
          case NONE:
            view.setVerticalFadingEdgeEnabled(false);
            view.setHorizontalFadingEdgeEnabled(false);
            break;
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      addAttributeProcessor(Attributes.View.TextAlignment, new StringAttributeProcessor<V>() {

        @SuppressLint("NewApi")
        @Override
        public void setString(V view, String value) {

          Integer textAlignment = ParseHelper.parseTextAlignment(value);
          if (null != textAlignment) {
            //noinspection ResourceType
            view.setTextAlignment(textAlignment);
          }
        }

      });
    }

    addAttributeProcessor(Attributes.View.Above, createRelativeLayoutRuleProcessor(RelativeLayout.ABOVE));
    addAttributeProcessor(Attributes.View.AlignBaseline, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_BASELINE));
    addAttributeProcessor(Attributes.View.AlignBottom, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_BOTTOM));
    addAttributeProcessor(Attributes.View.AlignLeft, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_LEFT));
    addAttributeProcessor(Attributes.View.AlignRight, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_RIGHT));
    addAttributeProcessor(Attributes.View.AlignTop, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_TOP));
    addAttributeProcessor(Attributes.View.Below, createRelativeLayoutRuleProcessor(RelativeLayout.BELOW));
    addAttributeProcessor(Attributes.View.ToLeftOf, createRelativeLayoutRuleProcessor(RelativeLayout.LEFT_OF));
    addAttributeProcessor(Attributes.View.ToRightOf, createRelativeLayoutRuleProcessor(RelativeLayout.RIGHT_OF));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      addAttributeProcessor(Attributes.View.AlignEnd, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_END));
      addAttributeProcessor(Attributes.View.AlignStart, createRelativeLayoutRuleProcessor(RelativeLayout.ALIGN_START));
      addAttributeProcessor(Attributes.View.ToEndOf, createRelativeLayoutRuleProcessor(RelativeLayout.END_OF));
      addAttributeProcessor(Attributes.View.ToStartOf, createRelativeLayoutRuleProcessor(RelativeLayout.START_OF));
    }

    addAttributeProcessor(Attributes.View.AlignParentTop, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_TOP));
    addAttributeProcessor(Attributes.View.AlignParentRight, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_RIGHT));
    addAttributeProcessor(Attributes.View.AlignParentBottom, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_BOTTOM));
    addAttributeProcessor(Attributes.View.AlignParentLeft, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_LEFT));
    addAttributeProcessor(Attributes.View.CenterHorizontal, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.CENTER_HORIZONTAL));
    addAttributeProcessor(Attributes.View.CenterVertical, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.CENTER_VERTICAL));
    addAttributeProcessor(Attributes.View.CenterInParent, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.CENTER_IN_PARENT));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      addAttributeProcessor(Attributes.View.AlignParentStart, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_START));
      addAttributeProcessor(Attributes.View.AlignParentEnd, createRelativeLayoutBooleanRuleProcessor(RelativeLayout.ALIGN_PARENT_END));
    }
  }

  @Override
  public boolean handleChildren(V view, Value children) {
    return false;
  }

  @Override
  public boolean addView(ProteusView parent, ProteusView view) {
    return false;
  }

  private AttributeProcessor<V> createRelativeLayoutRuleProcessor(final int rule) {
    return new StringAttributeProcessor<V>() {
      @Override
      public void setString(V view, String value) {
        if (view instanceof ProteusView) {
          int id = ((ProteusView) view).getViewManager().getContext().getInflater().getUniqueViewId(value);
          ParseHelper.addRelativeLayoutRule(view, rule, id);
        }
      }
    };
  }

  private AttributeProcessor<V> createRelativeLayoutBooleanRuleProcessor(final int rule) {
    return new BooleanAttributeProcessor<V>() {
      @Override
      public void setBoolean(V view, boolean value) {
        int trueOrFalse = ParseHelper.parseRelativeLayoutBoolean(value);
        ParseHelper.addRelativeLayoutRule(view, rule, trueOrFalse);
      }
    };
  }
}
