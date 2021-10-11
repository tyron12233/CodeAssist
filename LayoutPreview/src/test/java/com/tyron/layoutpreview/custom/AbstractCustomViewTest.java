package com.tyron.layoutpreview.custom;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.view.View;

import androidx.annotation.NonNull;

import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;
import com.tyron.layoutpreview.parser.WrapperUtils;

import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Class for testing CustomViews
 *
 * This class also handles generation of the json files, the output directory is located
 * at {@code app/src/test/resources/output}
 *
 * This will be used as the main tool for creating custom views for the LayoutPreview
 */
@RunWith(RobolectricTestRunner.class)
public abstract class AbstractCustomViewTest {

    private static final String MODULE_NAME = "LayoutPreview";

    /**
     * Subclasses must implement this to provide custom views.
     */
    @NonNull
    public abstract List<CustomView> getCustomViews();

    /**
     * Used by this class to get the class loader of the intended view
     */
    @NonNull
    public abstract ClassLoader getClassLoader();

    /**
     * Determines whether the custom view file should be generated
     */
    protected boolean isGenerated() {
        return false;
    }

    /**
     * Used to by this class to determine the output file name
     *
     * If {@link AbstractCustomViewTest#isGenerated()} returns true, subclasses must not
     * return null.
     */
    protected String getOutputName() {
        return null;
    }

    @Test
    public void testCustomViews() throws Exception {
        List<CustomView> customViews = getCustomViews();
        assertThat(customViews).isNotEmpty();

        for (CustomView customView : customViews) {
            assertThat(customView).isNotNull();
            assertThat(customView.getType()).isNotNull();
            assertThat(customView.getParentType()).isNotNull();

            testAttributes(customView);
        }
    }

    private void testAttributes(CustomView customView) throws Exception {
        for (Attribute attribute : customView.getAttributes()) {
            assertThat(attribute).isNotNull();
            assertThat(attribute.getXmlName()).isNotNull();
            assertThat(attribute.getMethodName()).isNotNull();
            assertThat(attribute.getParameters()).isNotNull();

            if (attribute.isLayoutParams()) {
                assertThat(attribute.getLayoutParamsClass()).isNotNull();
                assertWithMessage("Layout Params attribute can only have one parameter: " + attribute.getXmlName())
                        .that(attribute.getParameters().length)
                        .isEqualTo(1);

                try {
                    Class<?> layoutParamsClass = Class.forName(attribute.getLayoutParamsClass(), true, getClassLoader());
                    Field field = layoutParamsClass.getField(attribute.getMethodName());
                    assertWithMessage("LayoutParams field could not be found: " + attribute.getMethodName())
                            .that(field).isNotNull();
                    assertWithMessage("Field type from the LayoutParams class does not match. Found " + field.getType().getName() +
                            " expected: " + attribute.getParameters()[0])
                            .that(field.getType().getName())
                            .isEqualTo(attribute.getParameters()[0]);
                } catch (NoSuchFieldException e) {
                    System.out.println("WARNING: Unable to find method " + attribute.getMethodName() + " in " + attribute.getLayoutParamsClass());
                }
            } else {
                Class<? extends View> viewClass = Class.forName(customView.getType(), true, getClassLoader())
                        .asSubclass(View.class);
                Method method = WrapperUtils.getMethod(viewClass, attribute.getMethodName(), WrapperUtils.getParameters(attribute.getParameters()));
                assertWithMessage("Unable to find method " + attribute.getMethodName() + " in " + customView.getType())
                        .that(method).isNotNull();
            }

            if (attribute.getFormats().contains(Format.ENUM)) {
                assertThat(attribute.getEnumValues()).isNotNull();
                assertThat(attribute.getEnumValues()).isNotEmpty();
            }
        }

    }

    /**
     * Generates the custom views to the directory {@code app/src/test/resources/output}
     */
    @After
    public void generate() throws IOException {
        if (!isGenerated()) {
            return;
        }

        File outputDirectory = new File(resolveBasePath() + "/output/");
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Unable to create output directory");
        }

        File outputFile = new File(outputDirectory, getOutputName());
        if (!outputFile.exists() && !outputFile.createNewFile()) {
            throw new IOException("Unable to create output file");
        }
        String jsonString = new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(getCustomViews(), new TypeToken<List<CustomView>>(){}.getType());
        FileUtil.writeToFile(outputFile, jsonString);

        System.out.println("Generated file to " + outputFile.getAbsolutePath());
    }

    public static String resolveBasePath() {
        final String path = "./" + MODULE_NAME + "/src/test/resources";
        if (Arrays.asList(Objects.requireNonNull(new File("./").list())).contains(MODULE_NAME)) {
            return path;
        }
        return "../" + path;
    }
}
