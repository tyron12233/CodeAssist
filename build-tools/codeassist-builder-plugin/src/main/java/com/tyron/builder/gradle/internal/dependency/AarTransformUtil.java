package com.tyron.builder.gradle.internal.dependency;

import static com.android.SdkConstants.FD_JARS;
import static com.android.SdkConstants.FN_CLASSES_JAR;
import static com.android.SdkConstants.LIBS_FOLDER;

import com.google.common.collect.Lists;
import com.android.SdkConstants;

import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

class AarTransformUtil {

    static List<File> getJars(@NotNull File explodedAar) {
        List<File> files = Lists.newArrayList();
        File jarFolder = new File(explodedAar, FD_JARS);

        File file = GFileUtils.join(jarFolder, FN_CLASSES_JAR);
        if (file.isFile()) {
            files.add(file);
        }

        // local jars
        final File localJarFolder = new File(jarFolder, LIBS_FOLDER);
        File[] jars = localJarFolder.listFiles((dir, name) -> name.endsWith(SdkConstants.DOT_JAR));

        if (jars != null) {
            Arrays.sort(jars, Comparator.naturalOrder());
            files.addAll((Arrays.asList(jars)));
        }

        return files;
    }

}