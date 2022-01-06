package com.tyron.layout.constraintlayout.widget;

import static androidx.constraintlayout.widget.ConstraintLayout.*;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.processor.NumberAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layout.constraintlayout.view.ProteusConstraintLayout;

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
    public ProteusView createView(@NonNull ProteusContext context,
                                  @NonNull Layout layout,
                                  @NonNull ObjectValue data,
                                  @Nullable ViewGroup parent,
                                  int dataIndex) {
        return new ProteusConstraintLayout(context);
    }

    @Override
    protected void addAttributeProcessors() {
        addLayoutParamsAttributeProcessor("app:layout_constraintLeft_toLeftOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).leftToLeft =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID :
                                    ProteusHelper.getProteusContext(view)
                                            .getInflater()
                                            .getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintLeft_toRightOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).leftToRight =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID :
                                    ProteusHelper.getProteusContext(view)
                                            .getInflater()
                                            .getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintRight_toLeftOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).rightToLeft =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater()
                                    .getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintRight_toRightOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).rightToRight =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID :
                                    ProteusHelper.getProteusContext(view)
                                            .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintStart_toStartOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).startToStart =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintStart_toEndOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).startToEnd =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintEnd_toStartOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).endToStart =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintEnd_toEndOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).endToEnd =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));;
                }
            }
        });

        addLayoutParamsAttributeProcessor("app:layout_constraintBottom_toBottomOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).bottomToBottom =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintBottom_toTopOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).bottomToTop =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintTop_toBottomOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).topToBottom =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater().getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });
        addLayoutParamsAttributeProcessor("app:layout_constraintTop_toTopOf", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).topToTop =
                            value.equals("parent") ? ConstraintLayout.LayoutParams.PARENT_ID : ProteusHelper.getProteusContext(view)
                                    .getInflater()
                                    .getUniqueViewId(ParseHelper.parseViewId(value));
                }
            }
        });

        addLayoutParamsAttributeProcessor("app:layout_constraintVertical_bias", new NumberAttributeProcessor<T>() {
            @Override
            public void setNumber(T view, @NonNull Number value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).verticalBias =
                            value.floatValue();
                }
            }
        });

        addLayoutParamsAttributeProcessor("app:layout_constraintHorizontal_bias", new NumberAttributeProcessor<T>() {
            @Override
            public void setNumber(T view, @NonNull Number value) {
                if (view.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                    ((ConstraintLayout.LayoutParams) view.getLayoutParams()).horizontalBias =
                            value.floatValue();
                }
            }
        });

    }
}
