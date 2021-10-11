package com.tyron.builder.compiler.firebase;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.Project;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

public class GenerateFirebaseConfigTask extends Task {
    
    private static final String TAG = GenerateFirebaseConfigTask.class.getSimpleName();

    @Override
    public String getName() {
        return TAG;
    }

    private ILogger mLogger;
    private File mConfigFile;

    @Override
    public void prepare(Project project, ILogger logger, BuildType type) throws IOException {
        mLogger = logger;
        mConfigFile = new File(project.mRoot, "app/google-services.json");
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (!mConfigFile.exists()) {
            mLogger.debug("No google-services.json found.");
            return;
        }

        String contents = FileUtils.readFileToString(mConfigFile, Charset.defaultCharset());
        try {
            JSONObject jsonObject = new JSONObject(contents);
        } catch (JSONException e) {
            throw new CompilationFailedException("Failed to parse google-services.json: " + e.getMessage());
        }
    }
}
