package com.tyron.layout.appcompat.widget;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.NumberAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.processor.StyleResourceProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.android.material.textfield.TextInputLayout;
import com.tyron.layout.appcompat.R;
import com.tyron.layout.appcompat.view.ProteusTextInputLayout;

public class TextInputLayoutParser extends ViewTypeParser<View> {
    @NonNull
    @Override
    public String getType() {
        return TextInputLayout.class.getName();
    }

    @Nullable
    @Override
    public String getParentType() {
        return LinearLayout.class.getName();
    }

    @Nullable
    @Override
    protected String getDefaultStyleName() {
        return "?attr/textInputStyle";

    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout,
                                  @NonNull ObjectValue data, @Nullable ViewGroup parent,
                                  int dataIndex) {
        return new ProteusTextInputLayout(context);
    }

    @Override
    protected void addAttributeProcessors() {

        // TextInputLayout can take hints as attribute
        addAttributeProcessor(Attributes.TextView.Hint, new StringAttributeProcessor<View>() {
            @Override
            public void setString(View view, String value) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setHint(value);
                }
            }
        });

        addAttributeProcessor("app:boxBackgroundColor", new ColorResourceProcessor<View>() {
            @Override
            public void setColor(View view, int color) {
                if (view instanceof TextInputLayout) {
                    view.post(() -> ((TextInputLayout) view).setBoxBackgroundColor(color));
                }
            }

            @Override
            public void setColor(View view, ColorStateList colors) {
                if (view instanceof TextInputLayout) {
                    // wait for the box background mode to be set
                    view.post(() -> ((TextInputLayout) view).setBoxBackgroundColorStateList(colors));
                }
            }
        });

        addAttributeProcessor("app:elevation", new DimensionAttributeProcessor<View>() {
            @Override
            public void setDimension(View view, float dimension) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setElevation(dimension);
                }
            }
        });

        addAttributeProcessor("app:boxBackgroundMode", new StringAttributeProcessor<View>() {
            @Override
            public void setString(View view, String value) {
                int mode;
                switch (value) {
                    case "filled":
                        mode = TextInputLayout.BOX_BACKGROUND_FILLED;
                        break;
                    case "outline":
                        mode = TextInputLayout.BOX_BACKGROUND_OUTLINE;
                        break;
                    default:
                    case "none":
                        mode = TextInputLayout.BOX_BACKGROUND_NONE;
                        break;
                }
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setBoxBackgroundMode(mode);
                }
            }
        });

        addAttributeProcessor("app:boxCornerRadiusTopStart", addBoxCornerRadiusProcessor(0));
        addAttributeProcessor("app:boxCornerRadiusTopEnd", addBoxCornerRadiusProcessor(1));
        addAttributeProcessor("app:boxCornerRadiusBottomStart", addBoxCornerRadiusProcessor(2));
        addAttributeProcessor("app:boxCornerRadiusBottomEnd", addBoxCornerRadiusProcessor(3));

        addAttributeProcessor("app:boxStrokeWidth", new DimensionAttributeProcessor<View>() {
            @Override
            public void setDimension(View view, float dimension) {
                if (view instanceof TextInputLayout) {
                    view.post(() -> ((TextInputLayout) view).setBoxStrokeWidth((int) dimension));
                }
            }
        });

        addAttributeProcessor("app:boxStrokeWidthFocused", new DimensionAttributeProcessor<View>() {
            @Override
            public void setDimension(View view, float dimension) {
                if (view instanceof TextInputLayout) {
                    view.post(() -> ((TextInputLayout) view).setBoxStrokeWidthFocused((int) dimension));
                }
            }
        });

        addAttributeProcessor("app:boxStrokeColor", new ColorResourceProcessor<View>() {
            @Override
            public void setColor(View view, int color) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setBoxStrokeColor(color);
                }
            }

            @Override
            public void setColor(View view, ColorStateList colors) {
                if (view instanceof TextInputLayout) {
                    view.post(() -> ((TextInputLayout) view).setBoxStrokeColorStateList(colors));
                }
            }
        });

        addAttributeProcessor("app:boxStrokeErrorColor", new ColorResourceProcessor<View>() {
            @Override
            public void setColor(View view, int color) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setBoxStrokeErrorColor(ColorStateList.valueOf(color));
                }
            }

            @Override
            public void setColor(View view, ColorStateList colors) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setBoxStrokeErrorColor(colors);
                }
            }
        });

        addAttributeProcessor("app:counterEnabled", new BooleanAttributeProcessor<View>() {
            @Override
            public void setBoolean(View view, boolean value) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setCounterEnabled(value);
                }
            }
        });

        addAttributeProcessor("app:counterMaxLength", new NumberAttributeProcessor<View>() {
            @Override
            public void setNumber(View view, @NonNull Number value) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setCounterMaxLength(value.intValue());
                }
            }
        });

        addAttributeProcessor("app:counterTextColor", new ColorResourceProcessor<View>() {
            @Override
            public void setColor(View view, int color) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setCounterTextColor(ColorStateList.valueOf(color));
                }
            }

            @Override
            public void setColor(View view, ColorStateList colors) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setCounterTextColor(colors);
                }
            }
        });

        addAttributeProcessor("app:counterTextAppearance", new StyleResourceProcessor<>());
        addAttributeProcessor("app:hintTextAppearance", new StyleResourceProcessor<>());
        addAttributeProcessor("app:errorTextAppearance", new StyleResourceProcessor<>());
        addAttributeProcessor("app:counterOverflowTextAppearance", new StyleResourceProcessor<>());
        addAttributeProcessor("app:placeholderTextAppearance", new StyleResourceProcessor<>());
        addAttributeProcessor("app:helperTextTextAppearance", new StyleResourceProcessor<>());

        addAttributeProcessor(Attributes.TextView.TextColorHint, new ColorResourceProcessor<View>() {
            @Override
            public void setColor(View view, int color) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setDefaultHintTextColor(ColorStateList.valueOf(color));
                }
            }

            @Override
            public void setColor(View view, ColorStateList colors) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setDefaultHintTextColor(colors);
                }
            }
        });

        addAttributeProcessor("app:hintTextColor", new ColorResourceProcessor<View>() {
            @Override
            public void setColor(View view, int color) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setHintTextColor(ColorStateList.valueOf(color));
                }
            }

            @Override
            public void setColor(View view, ColorStateList colors) {
                if (view instanceof TextInputLayout) {
                    ((TextInputLayout) view).setHintTextColor(colors);
                }
            }
        });
    }

    /**
     * 0 = top start
     * 1 = top end
     * 2 = bottom start
     * 3 = bottom end
     */
    private DimensionAttributeProcessor<View> addBoxCornerRadiusProcessor(final int side) {
        return new DimensionAttributeProcessor<View>() {
            @Override
            public void setDimension(View view, float dimension) {
                if (view instanceof TextInputLayout) {
                    float[] radii = getBoxCornerRadii(((TextInputLayout) view));
                    radii[side] = dimension;
                    ((TextInputLayout) view).setBoxCornerRadii(radii[0], radii[1], radii[2], radii[3]);
                }
            }
        };
    }

    private float[] getBoxCornerRadii(TextInputLayout layout) {
        float[] radii = new float[4];
        radii[0] = layout.getBoxCornerRadiusTopStart();
        radii[1] = layout.getBoxCornerRadiusTopEnd();
        radii[2] = layout.getBoxCornerRadiusBottomStart();
        radii[3] = layout.getBoxCornerRadiusBottomEnd();
        return radii;
    }
}
