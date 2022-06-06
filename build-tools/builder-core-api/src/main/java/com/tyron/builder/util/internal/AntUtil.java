package com.tyron.builder.util.internal;

import org.apache.tools.ant.MagicNames;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.Task;
    import com.tyron.builder.api.internal.project.ant.AntLoggingAdapter;

public class AntUtil {
    /**
     * @return Factory method to create new Project instances
     */
    public static Project createProject() {
        final Project project = new Project();

        final ProjectHelper helper = ProjectHelper.getProjectHelper();
        project.addReference(MagicNames.REFID_PROJECT_HELPER, helper);
        helper.getImportStack().addElement("AntBuilder"); // import checks that stack is not empty

        project.addBuildListener(new AntLoggingAdapter());

        project.init();
        project.getBaseDir();
        return project;
    }

    public static void execute(Task task) {
        task.setProject(createProject());
        task.execute();
    }

    /**
     * Masks a string against Ant property expansion.
     * This needs to be used when adding a File as String property
     * via {@link groovy.ant.AntBuilder}.
     * @param string to mask
     * @return The masked String
     */
    public static String maskFilename(String string) {
            return string.replaceAll("\\$", "\\$\\$");
    }
}
