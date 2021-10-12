package com.tyron.layout.constraintlayout.widget;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.NumberAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;

public class ConstraintLayoutParser<T extends View> extends ViewTypeParser<T> {

    @NonNull
    @Override
    public String getType() {
        return "androidx.constraintlayout.widget.ConstraintLayout";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.view.ViewGroup";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return null;
    }

    @Override
    protected void addAttributeProcessors() {
        addAttributeProcessor("app:layout_constraintLeft_toLeftOf", createConstraintLayoutRuleProcessor(ConstraintSet.LEFT, ConstraintSet.LEFT));
        addAttributeProcessor("app:layout_constraintLeft_toRightOf", createConstraintLayoutRuleProcessor(ConstraintSet.LEFT, ConstraintSet.RIGHT));
        addAttributeProcessor("app:layout_constraintRight_toLeftOf", createConstraintLayoutRuleProcessor(ConstraintSet.RIGHT, ConstraintSet.LEFT));
        addAttributeProcessor("app:layout_constraintRight_toRightOf", createConstraintLayoutRuleProcessor(ConstraintSet.RIGHT, ConstraintSet.RIGHT));
        addAttributeProcessor("app:layout_constraintStart_toStartOf", createConstraintLayoutRuleProcessor(ConstraintSet.START, ConstraintSet.START));
        addAttributeProcessor("app:layout_constraintStart_toEndOf", createConstraintLayoutRuleProcessor(ConstraintSet.START, ConstraintSet.END));
        addAttributeProcessor("app:layout_constraintEnd_toStartOf", createConstraintLayoutRuleProcessor(ConstraintSet.END, ConstraintSet.START));
        addAttributeProcessor("app:layout_constraintEnd_toEndOf", createConstraintLayoutRuleProcessor(ConstraintSet.END, ConstraintSet.END));

        addAttributeProcessor("app:layout_constraintBottom_toBottomOf", createConstraintLayoutRuleProcessor(ConstraintSet.BOTTOM, ConstraintSet.BOTTOM));
        addAttributeProcessor("app:layout_constraintBottom_toTopOf", createConstraintLayoutRuleProcessor(ConstraintSet.BOTTOM, ConstraintSet.TOP));
        addAttributeProcessor("app:layout_constraintTop_toBottomOf", createConstraintLayoutRuleProcessor(ConstraintSet.TOP, ConstraintSet.BOTTOM));
        addAttributeProcessor("app:layout_constraintTop_toTopOf", createConstraintLayoutRuleProcessor(ConstraintSet.TOP, ConstraintSet.TOP));

        addAttributeProcessor("app:layout_constraintVertical_bias", new NumberAttributeProcessor<T>() {
            @Override
            public void setNumber(T view, @NonNull Number value) {
                ConstraintSet set = getConstraintSet(view);
                if (set != null) {
                    set.setVerticalBias(view.getId(), value.floatValue());
                    apply(set, view);
                }
            }
        });

        addAttributeProcessor("app:layout_constraintHorizontal_bias", new NumberAttributeProcessor<T>() {
            @Override
            public void setNumber(T view, @NonNull Number value) {
                ConstraintSet set = getConstraintSet(view);
                if (set != null) {
                    set.setHorizontalBias(view.getId(), value.floatValue());
                    apply(set, view);
                }
            }
        });

    }

    private AttributeProcessor<T> createConstraintLayoutRuleProcessor(int startSide, int endSide) {
        return new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                ConstraintSet set = getConstraintSet(view);
                if (set != null) {
                    ConstraintLayout parent = (ConstraintLayout) view.getParent();
                    int id;
                    if (value.equals("parent")) {
                       id = ConstraintSet.PARENT_ID;
                    } else {
                        id = ((ProteusView) view).getViewManager().getContext()
                                .getInflater().getUniqueViewId(value);
                    }
                    set.connect(view.getId(), startSide, id, endSide);
                    set.applyTo(parent);
                }
            }
        };
    }

    private ConstraintSet getConstraintSet(View view) {
        if (view.getParent() instanceof ConstraintLayout) {
            ConstraintLayout parent = (ConstraintLayout) view.getParent();
            ConstraintSet set = new ConstraintSet();
            set.clone(parent);
            return set;
        }
        return null;
    }

    private void apply(ConstraintSet set, View view) {
        if (view.getParent() instanceof ConstraintLayout) {
            set.applyTo((ConstraintLayout) view.getParent());
        }
    }
}
