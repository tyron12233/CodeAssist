package com.tyron.builder.crashlytics;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.xml.completion.repository.Repository;
import com.tyron.xml.completion.repository.ResourceItem;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;

import org.apache.commons.io.FileUtils;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.intellij.lang.annotations.Language;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Task to inject crashlytics build id to the resource directory
 */
public class CrashlyticsTask extends Task<AndroidModule> {

    private static final String TAG = CrashlyticsTask.class.getSimpleName();

    private static final String LEGACY_MAPPING_FILE_ID_RESOURCE_NAME =
            "com_crashlytics_android_build_id";
    private static final String CORE_CLASS =
            "com.google.firebase.crashlytics.internal.common.CrashlyticsCore";
    private static final String CRASHLYTICS_RESOURCE_FILE = "code_assist_crashlytics__.xml";
    private static final CharSequence CRASHLYTICS_BUILD_ID = "code_assist_crashlytics_task_0282";
    private static final String XML_REPOSITORY_CLASS = "com.tyron.completion.xml.XmlRepository";

    private boolean mContainsCrashlytics;

    public CrashlyticsTask(Project project, AndroidModule module, ILogger logger) {
        super(project, module, logger);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {
        mContainsCrashlytics = getModule().getAllClasses().contains(CORE_CLASS);
    }

    @Override
    public void run() throws IOException, CompilationFailedException {
        if (!mContainsCrashlytics) {
            return;
        }

        Repository repository = getRepository(getProject(), getModule());
        if (repository == null) {
            getLogger().warning("Unable to get repository.");
            return;
        }

        ResourceNamespace namespace = repository.getNamespace();
        ResourceReference resourceReference = new ResourceReference(namespace, ResourceType.STRING,
                                                                    LEGACY_MAPPING_FILE_ID_RESOURCE_NAME);
        List<ResourceItem> resources = repository.getResources(resourceReference);
        if (!resources.isEmpty()) {
            return;
        }

        File valuesDir = new File(getModule().getAndroidResourcesDirectory(), "values");
        if (!valuesDir.exists() && !valuesDir.mkdirs()) {
            throw new IOException("Unable to create values directory");
        }
        File resFile = new File(valuesDir, CRASHLYTICS_RESOURCE_FILE);
        if (!resFile.exists() && !resFile.createNewFile()) {
            throw new IOException("Unable to create crashlytics resource file");
        }

        @Language("XML") String contents =
                "<resources>\n" + "    <string name=\"$name\">$value</string>\n" + "</resources>";
        contents = contents.replace("$name", LEGACY_MAPPING_FILE_ID_RESOURCE_NAME);
        contents = contents.replace("$value", CRASHLYTICS_BUILD_ID);

        FileUtils.writeStringToFile(resFile, contents, StandardCharsets.UTF_8);
    }

    public static Repository getRepository(Project project, AndroidModule module) {
        try {
            Class<?> clazz = Class.forName(XML_REPOSITORY_CLASS);
            Method method =
                    clazz.getDeclaredMethod("getRepository", Project.class, AndroidModule.class);
            Object invoke = method.invoke(null, project, module);

            Method getRepository = clazz.getDeclaredMethod("getRepository");
            Object invoke1 = getRepository.invoke(invoke);
            if (invoke1 instanceof Repository) {
                return ((Repository) invoke1);
            }
            return null;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
