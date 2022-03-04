package com.tyron.builder.compiler.firebase;

import androidx.annotation.VisibleForTesting;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class GenerateFirebaseConfigTask extends Task<AndroidModule> {

    private static final String TAG = GenerateFirebaseConfigTask.class.getSimpleName();

    private static final String VALUES = "values";
    private static final String CLIENT = "client";
    private static final String API_KEY = "api_key";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_INFO = "client_info";
    private static final String CURRENT_KEY = "current_key";
    private static final String OAUTH_CLIENT = "oauth_client";
    private static final String PROJECT_INFO = "project_info";
    private static final String PACKAGE_NAME = "package_name";
    private static final String STORAGE_BUCKET = "storage_bucket";
    private static final String FIREBASE_URL = "firebase_url";
    private static final String FIREBASE_DATABASE_URL = "firebase_database_url";
    private static final String ANDROID_CLIENT_INFO = "android_client_info";
    private static final String MOBILESDK_SDK_APP_ID = "mobilesdk_app_id";
    private static final String DEFAULT_WEB_CLIENT_ID = "default_web_client_id";
    private static final String GOOGLE_SERVICES_JSON = "google-services.json";
    private static final String GOOGLE_STORAGE_BUCKET = "google_storage_bucket";
    private static final String GOOGLE_API_KEY = "google_api_key";
    private static final String GOOGLE_APP_ID = "google_app_id";
    private static final String GOOGLE_CRASH_REPORTING_API_KEY = "google_crash_reporting_api_key";

    public GenerateFirebaseConfigTask(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    private File mConfigFile;

    @Override
    public void prepare(BuildType type) throws IOException {
        mConfigFile = new File(getModule().getRootFile(), GOOGLE_SERVICES_JSON);
    }

    /**
     * Processes google-services.json and outputs it to res/xml
     * <p>
     * According to https://firebase.google.com/docs/reference/gradle/#processing_the_json_file
     */
    @Override
    public void run() throws IOException, CompilationFailedException {
        if (!mConfigFile.exists()) {
            getLogger().debug("No google-services.json found.");
            return;
        }

        String contents = FileUtils.readFileToString(mConfigFile, Charset.defaultCharset());
        try {
            File xmlDirectory = new File(getModule().getAndroidResourcesDirectory(), VALUES);
            if (!xmlDirectory.exists() && !xmlDirectory.mkdirs()) {
                throw new IOException("Unable to create xml folder");
            }

            File secretsFile = new File(xmlDirectory, "secrets.xml");
            if (!secretsFile.exists() && !secretsFile.createNewFile()) {
                throw new IOException("Unable to create secrets.xml file");
            }

            if (doGenerate(contents, getModule().getPackageName(), secretsFile)) {
                return;
            }

            String message = "" +
                    "Unable to find " + getModule().getPackageName() +
                    " in google-services.json. \n" +
                    "Ensure that the package name defined in your firebase console " +
                    "matches your app's package name.";
            throw new CompilationFailedException(message);
        } catch (JSONException e) {
            throw new CompilationFailedException("Failed to parse google-services.json: " +
                    e.getMessage());
        }
    }

    @VisibleForTesting
    public boolean doGenerate(String contents, String packageName, File secretsFile)
            throws JSONException, IOException {
        JSONObject jsonObject = new JSONObject(contents);
        JSONObject projectInfo = jsonObject.getJSONObject(PROJECT_INFO);

        JSONArray clientArray = jsonObject.getJSONArray(CLIENT);
        for (int i = 0; i < clientArray.length(); i++) {
            JSONObject object = clientArray.getJSONObject(i);
            JSONObject clientInfo = object.getJSONObject(CLIENT_INFO);
            JSONObject androidClientInfo = clientInfo.getJSONObject(ANDROID_CLIENT_INFO);

            String clientPackageName = androidClientInfo.getString(PACKAGE_NAME);
            if (packageName.equals(clientPackageName)) {
                parseConfig(projectInfo, object, clientInfo, secretsFile);
                return true;
            }
        }

        return false;
    }

    private void parseConfig(JSONObject projectInfo,
                             JSONObject client,
                             JSONObject clientInfo,
                             File secretsFile) throws JSONException, IOException {
        Iterator<String> keys = projectInfo.keys();
        Map<String, String> map = new HashMap<>();
        keys.forEachRemaining(s -> {
            String replacedKey = replaceKey(s);
            try {
                map.put(replacedKey, projectInfo.getString(s));
            } catch (JSONException e) {
                String message = "" +
                        "Failed to put value to secrets.xml.\n" +
                        "Key: " + s + "\n" +
                        "Error: " + e.getMessage();
                getLogger().warning(message);
            }
        });

        try {
            String mobileSdkAppId = clientInfo.getString(MOBILESDK_SDK_APP_ID);
            map.put(GOOGLE_APP_ID, mobileSdkAppId);
        } catch (JSONException ignored) {

        }

        try {
            String oathClientId = client.getJSONObject(OAUTH_CLIENT)
                    .getString(CLIENT_ID);
            map.put(DEFAULT_WEB_CLIENT_ID, oathClientId);
        } catch (JSONException ignore) {

        }

        try {
            String apiKey = client.getJSONArray(API_KEY)
                    .getJSONObject(0)
                    .getString(CURRENT_KEY);
            map.put(GOOGLE_API_KEY, apiKey);
            map.put(GOOGLE_CRASH_REPORTING_API_KEY, apiKey);
        } catch (JSONException e) {
            getLogger().warning("Unable to put api keys, error: " + e.getMessage());
        }


        generateXML(map, secretsFile);
    }

    private void generateXML(Map<String, String> config, File secretsFile) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(secretsFile.toPath())) {
            writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            writer.write("<resources>\n");
            writer.write("\t<integer name=\"google_play_services_version\">12451000</integer>\n");
            for (Map.Entry<String, String> entry : config.entrySet()) {
                writer.write("\t<string name=\"");
                writer.write(entry.getKey());
                writer.write("\" translatable=\"false\">");
                writer.write(entry.getValue());
                writer.write("</string>\n");
            }
            writer.write("</resources>");
        }
    }

    private String replaceKey(String key) {
        if (STORAGE_BUCKET.equals(key)) {
            return GOOGLE_STORAGE_BUCKET;
        }
        if (FIREBASE_URL.equals(key)) {
            return FIREBASE_DATABASE_URL;
        }
        return key;
    }

}
