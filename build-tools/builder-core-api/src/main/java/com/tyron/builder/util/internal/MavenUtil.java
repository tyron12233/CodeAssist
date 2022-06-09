package com.tyron.builder.util.internal;

import com.tyron.builder.internal.SystemProperties;

import javax.annotation.Nullable;
import java.io.File;

public class MavenUtil {

    public static File getUserMavenDir() {
        return new File(SystemProperties.getInstance().getUserHome(), ".m2");
    }

    @Nullable
    public static File getGlobalMavenDir() {
        String m2Home = System.getenv("M2_HOME");
        if (m2Home == null) {
            return null;
        }
        return new File(m2Home);
    }

}
