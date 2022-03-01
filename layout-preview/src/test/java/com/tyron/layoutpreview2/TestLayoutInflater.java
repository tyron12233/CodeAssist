package com.tyron.layoutpreview2;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.TestUtil;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.xml.completion.repository.ResourceRepository;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.util.DOMUtils;
import com.tyron.layoutpreview2.attr.impl.TextViewAttributeApplier;
import com.tyron.layoutpreview2.attr.impl.ViewAttributeApplier;
import com.tyron.layoutpreview2.view.impl.AndroidViewImpl;
import com.tyron.layoutpreview2.view.impl.EditorLinearLayout;
import com.tyron.layoutpreview2.view.impl.EditorTextView;

import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMParser;
import org.intellij.lang.annotations.Language;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = "src/main/AndroidManifest.xml", sdk = 26, resourceDir = "src/main/res")
public class TestLayoutInflater {

    @Language("XML")
    private static final String TEST_LAYOUT = "<LinearLayout\n" +
                                              "    xmlns:android=\"http://schemas.android" +
                                              ".com/apk/res/android\"\n" +
                                              "    android:layout_width=\"match_parent\"\n" +
                                              "    android:layout_height=\"100dp\">\n" +
                                              "    \n" + "    <TextView\n" + "    \n" +
                                              "   android:layout_height=\"wrap_content\"\n" +
                                              "     " +
                                              "   android:layout_width=\"match_parent\"" +
                                              " \n" + "        android:text=\"@string/app_name\"/>\n" +
                                              "</LinearLayout>";

    private EditorInflater mInflater;

    @Before
    public void setup() throws IOException {
        ApplicationProvider
                .initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext());

        File resourcesDirectory = TestUtil.getResourcesDirectory();
        MockAndroidModule module =
                new MockAndroidModule(resourcesDirectory, new MockFileManager(resourcesDirectory));
        module.setPackageName("com.tyron.test");
        module.setAndroidResourcesDirectory(new File(resourcesDirectory, "test_res"));
        module.open();

        ResourceRepository.setInitializeAndroidRepo(false);

        XmlRepository repository = new XmlRepository();
        repository.initialize(module);

        EditorContext context = new EditorContext(ApplicationProvider.getApplicationContext());
        context.setRepository(repository.getRepository());
        registerEditorViews(context);
        registerAttributeAppliers(context);

        mInflater = new PhoneLayoutInflater(context);
    }

    private void registerEditorViews(EditorContext context) {
        context.registerMapping(View.class, AndroidViewImpl.class);
        context.registerMapping(LinearLayout.class, EditorLinearLayout.class);
        context.registerMapping(TextView.class, EditorTextView.class);
    }

    private void registerAttributeAppliers(EditorContext context) {
        context.registerAttributeApplier(new ViewAttributeApplier());
        context.registerAttributeApplier(new TextViewAttributeApplier());
    }

    @Test
    public void testInflate() {
        DOMDocument document = DOMParser.getInstance().parse(TEST_LAYOUT, "", null);
        DOMUtils.setNamespace(document, ResourceNamespace.fromPackageName("com.tyron.test"));

        View inflate = mInflater.inflate(document, null, false);
        assert inflate instanceof LinearLayout;

        LinearLayout root = (LinearLayout) inflate;
        assert root.getChildCount() == 1;

        View child = root.getChildAt(0);
        assert child instanceof TextView;

        TextView textView = ((TextView) child);
        assert "TEST".equals(textView.getText().toString()) : textView.getText().toString();
    }
}
