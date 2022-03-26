package com.tyron.builder.compiler.manifest;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.truth.Truth;
import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.compiler.manifest.xml.IAbstractFile;
import com.tyron.builder.compiler.manifest.xml.IAbstractFolder;
import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.compiler.manifest.xml.StreamException;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AndroidManifestParserTest {

    private static final String TEST_MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    package=\"com.tyron.test\">\n" +
            "    \n" +
            "    <application>\n" +
            "        android:name=\".TestApplication\"\n" +
            "        \n" +
            "        <activity\n" +
            "            android:name=\".TestActivity\"/>\n" +
            "        \n" +
            "        <service\n" +
            "            android:name=\".TestService\"/>\n" +
            "    " +
            "</application>\n" +
            "</manifest>";

    private static final String EXPECTED_PACKAGE = "com.tyron.test";
    private static final String EXPECTED_ACTIVITY_NAME = EXPECTED_PACKAGE + ".TestActivity";
    @Test
    public void parse() throws Exception {
        ManifestData parse = AndroidManifestParser.parse(new ManifestFile(TEST_MANIFEST));
        assertThat(parse).isNotNull();

        assertThat(parse.getPackage())
                .isEqualTo(EXPECTED_PACKAGE);

        assertThat(parse.getActivities())
                .hasLength(1);
        assertThat(parse.getActivities()[0].getName())
                .isEqualTo(EXPECTED_ACTIVITY_NAME);
    }
}
