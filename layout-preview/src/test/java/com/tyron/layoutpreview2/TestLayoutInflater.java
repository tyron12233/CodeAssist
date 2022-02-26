package com.tyron.layoutpreview2;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.TestUtil;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.repository.ResourceRepository;

import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMParser;
import org.intellij.lang.annotations.Language;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class TestLayoutInflater {

    @Language("XML")
    private static final String TEST_LAYOUT = "<LinearLayout\n" +
                                              "    xmlns:android=\"http://schemas.android" +
                                              ".com/apk/res/android\"\n" +
                                              "    android:layout_width=\"match_parent\"\n" +
                                              "    android:layout_height=\"match_parent\">\n" +
                                              "    \n" + "    <TextView\n" + "    \n" +
                                              "   android:layout_height=\"wrap_content\"\n" +
                                              "     " +
                                              "   android:layout_width=\"match_parent\"" +
                                              " \n" + "        android:text=\"Hello world!\"/>\n" +
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

        mInflater = new PhoneLayoutInflater(ApplicationProvider.getApplicationContext(),
                                       repository.getRepository());
    }

    @Test
    public void testInflate() {
        DOMDocument document = DOMParser.getInstance().parse(TEST_LAYOUT, "", null);
        View inflate = mInflater.inflate(document, null, false);
        assert inflate instanceof LinearLayout;

        LinearLayout root = (LinearLayout) inflate;
        assert root.getChildCount() == 1;

        View child = root.getChildAt(0);
        assert child instanceof TextView;
    }
}
