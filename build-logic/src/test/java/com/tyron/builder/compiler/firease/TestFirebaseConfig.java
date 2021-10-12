package com.tyron.builder.compiler.firease;

import com.google.common.truth.Truth;
import com.tyron.builder.compiler.firebase.GenerateFirebaseConfigTask;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

@RunWith(RobolectricTestRunner.class)
public class TestFirebaseConfig {

    private static final String PACKAGE_NAME = "com.google.samples.apps.iosched.tv";
    private static final String SERVICES_CONFIG = "{\n" +
            "  \"project_info\": {\n" +
            "    \"project_number\": \"447780894619\",\n" +
            "    \"firebase_url\": \"https://events-dev-62d2e.firebaseio.com\",\n" +
            "    \"project_id\": \"events-dev-62d2e\",\n" +
            "    \"storage_bucket\": \"events-dev-62d2e.appspot.com\"\n" +
            "  },\n" +
            "  \"client\": [\n" +
            "    {\n" +
            "      \"client_info\": {\n" +
            "        \"mobilesdk_app_id\": \"1:447780894619:android:4fafa826cd727889\",\n" +
            "        \"android_client_info\": {\n" +
            "          \"package_name\": \"com.google.samples.apps.iosched.tv\"\n" +
            "        }\n" +
            "      },\n" +
            "      \"oauth_client\": [\n" +
            "        {\n" +
            "          \"client_id\": \"447780894619-an6s48nvj18f25v4nc5te03q2c4g9dqf.apps.googleusercontent.com\",\n" +
            "          \"client_type\": 3\n" +
            "        },\n" +
            "        {\n" +
            "          \"client_id\": \"447780894619-tbe57ou9oflnoic5scbc8mj8tnnj9o2r.apps.googleusercontent.com\",\n" +
            "          \"client_type\": 3\n" +
            "        }\n" +
            "      ],\n" +
            "      \"api_key\": [\n" +
            "        {\n" +
            "          \"current_key\": \"AIzaSyC_LbkKaCrAaBJSBp7DbDZgwLLR3BYUJV0\"\n" +
            "        }\n" +
            "      ],\n" +
            "      \"services\": {\n" +
            "        \"analytics_service\": {\n" +
            "          \"status\": 1\n" +
            "        },\n" +
            "        \"appinvite_service\": {\n" +
            "          \"status\": 1,\n" +
            "          \"other_platform_oauth_client\": []\n" +
            "        },\n" +
            "        \"ads_service\": {\n" +
            "          \"status\": 2\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  ],\n" +
            "  \"configuration_version\": \"1\"\n" +
            "}";

    @Test
    public void test() throws IOException, JSONException, XmlPullParserException {
        File output = File.createTempFile("secrets", ".xml");
        GenerateFirebaseConfigTask task = new GenerateFirebaseConfigTask();
        task.doGenerate(SERVICES_CONFIG, "com.google.samples.apps.iosched.tv", output);

        String contents = FileUtils.readFileToString(output, Charset.defaultCharset());
        Truth.assertThat(contents)
                .isNotEmpty();
    }
}
