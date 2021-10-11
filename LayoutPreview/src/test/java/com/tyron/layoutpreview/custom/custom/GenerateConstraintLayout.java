package com.tyron.layoutpreview.custom.custom;

import androidx.annotation.NonNull;

import com.tyron.layoutpreview.custom.AbstractCustomViewTest;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GenerateConstraintLayout extends AbstractCustomViewTest {

    private static final boolean GENERATE = true;

    @NonNull
    @Override
    public List<CustomView> getCustomViews() {
        return Collections.singletonList(getConstraint());
    }

    @NonNull
    @Override
    public ClassLoader getClassLoader() {
        URL resource = Objects.requireNonNull(this.getClass().getClassLoader()).getResource("custom_views/constraintlayout/classes.jar");
        return new URLClassLoader(new URL[]{resource});
    }

    @Override
    protected boolean isGenerated() {
        return GENERATE;
    }

    @Override
    protected String getOutputName() {
        return "ConstraintLayout.json";
    }

    @SuppressWarnings("ALL")
    private CustomView getConstraint() {
        CustomView view = new CustomView();
        view.setType("androidx.constraintlayout.widget.ConstraintLayout");
        view.setParentType("android.view.ViewGroup");
        view.setViewGroup(true);

        Attribute leftToLeft = Attribute.builder()
                .setLayoutParams(true)
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintLeft_toLeftOf")
                .setMethodName("leftToLeft")
                .setParameters(int.class)
                .build();

        Attribute rightToRight = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintRight_toRightOf")
                .setMethodName("rightToRight")
                .setParameters(int.class)
                .build();

        Attribute rightToLeft = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintRight_toLeftOf")
                .setMethodName("rightToLeft")
                .setParameters(int.class)
                .build();

        Attribute leftToRight = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintLeft_toRightOf")
                .setMethodName("leftToRight")
                .setParameters(int.class)
                .build();

        Attribute topToTop = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintTop_toTopOf")
                .setMethodName("topToTop")
                .setParameters(int.class)
                .build();

        Attribute topToBottom = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintTop_toBottomOf")
                .setMethodName("topToBottom")
                .setParameters(int.class)
                .build();

        Attribute bottomToTop = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintBottom_toTopOf")
                .setMethodName("bottomToTop")
                .setParameters(int.class)
                .build();

        Attribute bottomToBottom = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintBottom_toBottomOf")
                .setMethodName("bottomToBottom")
                .setParameters(int.class)
                .build();

        Attribute startToStart = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintStart_toStartOf")
                .setMethodName("startToStart")
                .setParameters(int.class)
                .build();

        Attribute startToEnd = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintStart_toEndOf")
                .setMethodName("startToEnd")
                .setParameters(int.class)
                .build();

        Attribute endToEnd = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintEnd_toEndOf")
                .setMethodName("endToEnd")
                .setParameters(int.class)
                .build();

        Attribute endToStart = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.REFERENCE)
                .addFormat(Format.ENUM)
                .setEnumValues(Collections.singletonMap("parent", 0))
                .setXmlName("app:layout_constraintEnd_toStartOf")
                .setMethodName("endToStart")
                .setParameters(int.class)
                .build();

        Attribute goneMarginLeft = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_goneMarginLeft")
                .setMethodName("goneLeftMargin")
                .setParameters(int.class)
                .build();

        Attribute goneMarginRight = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_goneMarginRight")
                .setMethodName("goneRightMargin")
                .setParameters(int.class)
                .build();

        Attribute goneMarginTop = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_goneMarginTop")
                .setMethodName("goneTopMargin")
                .setParameters(int.class)
                .build();

        Attribute goneMarginBottom = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_goneMarginBottom")
                .setMethodName("goneBottomMargin")
                .setParameters(int.class)
                .build();

        Attribute goneMarginStart = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_goneMarginStart")
                .setMethodName("goneStartMargin")
                .setParameters(int.class)
                .build();

        Attribute goneMarginEnd = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_goneMarginStart")
                .setMethodName("goneEndMargin")
                .setParameters(int.class)
                .build();

        Attribute horizontalBias = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.FLOAT)
                .setXmlName("app:layout_constraintHorizontal_bias")
                .setMethodName("horizontalBias")
                .setParameters(float.class)
                .build();

        Attribute verticalBias = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.FLOAT)
                .setXmlName("app:layout_constraintVertical_bias")
                .setMethodName("verticalBias")
                .setParameters(float.class)
                .build();

        Attribute horizontalChainStyle = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.ENUM)
                .setEnumValues(Map.of("spread", 0, "spread_inside", 1, "packed", 2))
                .setXmlName("app:layout_constraintHorizontal_chainStyle")
                .setMethodName("horizontalChainStyle")
                .setParameters(int.class)
                .build();

        Attribute verticalChainStyle = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.ENUM)
                .setEnumValues(Map.of("spread", 0, "spread_inside", 1, "packed", 2))
                .setXmlName("app:layout_constraintVertical_chainStyle")
                .setMethodName("verticalChainStyle")
                .setParameters(int.class)
                .build();

        Attribute marginBaseline = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_marginBaseline")
                .setMethodName("baselineMargin")
                .setParameters(int.class)
                .build();

        Attribute goneMarginBaseline = Attribute.builder()
                .setLayoutParamsClass("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams")
                .addFormat(Format.DIMENSION)
                .setXmlName("app:layout_marginBaseline")
                .setMethodName("goneBaselineMargin")
                .setParameters(int.class)
                .build();

        view.setAttributes(Arrays.asList(leftToLeft, rightToRight, rightToLeft, leftToRight,
                topToTop, topToBottom, bottomToTop, bottomToBottom, startToStart, startToEnd,
                endToEnd, endToStart, goneMarginLeft, goneMarginRight, goneMarginTop, goneMarginBottom,
                goneMarginStart, goneMarginEnd, horizontalBias, verticalBias, horizontalChainStyle,
                verticalChainStyle, marginBaseline, goneMarginBaseline));
        return view;
    }

}
