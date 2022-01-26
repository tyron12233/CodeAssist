package com.tyron.builder.compiler.firebase;

import androidx.annotation.VisibleForTesting;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
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

    public GenerateFirebaseConfigTask(AndroidModule project, ILogger logger) {
        super(project, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    private File mConfigFile;

    @Override
    public void prepare(BuildType type) throws IOException {
        mConfigFile = new File(getModule().getRootFile(), "google-services.json");
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
            File xmlDirectory = new File(getModule().getAndroidResourcesDirectory(), "values");
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
            throw new CompilationFailedException("Unable to find " + getModule().getPackageName() +
                    " in google-services.json");
        } catch (JSONException e) {
            throw new CompilationFailedException("Failed to parse google-services.json: " +
                    e.getMessage());
        }
    }

    @VisibleForTesting
    public boolean doGenerate(String contents, String packageName, File secretsFile)
            throws JSONException, IOException {
        JSONObject jsonObject = new JSONObject(contents);
        JSONObject projectInfo = jsonObject.getJSONObject("project_info");

        JSONArray clientArray = jsonObject.getJSONArray("client");
        for (int i = 0; i < clientArray.length(); i++) {
            JSONObject object = clientArray.getJSONObject(i);
            JSONObject clientInfo = object.getJSONObject("client_info");
            JSONObject androidClientInfo = clientInfo.getJSONObject("android_client_info");

            String clientPackageName = androidClientInfo.getString("package_name");
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
            try {
                map.put(s, projectInfo.getString(s));
            } catch (JSONException e) {
                String message = "" +
                        "Failed to put value to secrets.xml.\n" +
                        "Key: " + s + "\n" +
                        "Error: " + e.getMessage();
                getLogger().warning(message);
            }
        });

        String mobileSdkAppId = clientInfo.getString("mobilesdk_app_id");
        map.put("google_app_id", mobileSdkAppId);

        try {
            String oathClientId = client.getJSONObject("oauth_client")
                    .getString("client_id");
            map.put("default_web_client_id", oathClientId);
        } catch (JSONException ignore) {

        }

        try {
            String apiKey = client.getJSONArray("api_key")
                    .getJSONObject(0)
                    .getString("current_key");
            map.put("google_api_key", apiKey);
            map.put("google_crash_reporting_api_key", apiKey);
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

}