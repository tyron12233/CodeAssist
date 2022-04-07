package com.tyron.builder.compiler.incremental.resource;
import org.apache.commons.io.FileUtils;
import com.android.tools.aapt2.Aapt2Jni;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BuildConfigTask extends Task<AndroidModule> {
	private String buildConfig;

    private static final String TAG = "BuildConfig";

	private String b;

    public BuildConfigTask(Project project,
						   AndroidModule module,
						   ILogger logger
						   ) {
        super(project, module, logger);

    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
				
		buildConfig = "/**\n * Automatically generated file. DO NOT MODIFY\n*/\npackage %pack%;\npublic final class BuildConfig {\npublic static final boolean DEBUG = false;\npublic static final String APPLICATION_ID = \"%pack%\";\npublic static final String BUILD_TYPE = \"%debug%\";\npublic static final int VERSION_CODE = 1;\npublic static final String VERSION_NAME = \"1.0.0\";\n}".replace("%pack%", getModule().getPackageName()).replace("%debug%", String.valueOf( type));
	}
    public void run() throws IOException, CompilationFailedException {

		File file = new File(getModule().getBuildDirectory().getAbsolutePath() + "/gen" + "/" + getModule().getPackageName().replace(".", "/") + "/BuildConfig.java");
		FileUtils.write(file, buildConfig);
    }

}

