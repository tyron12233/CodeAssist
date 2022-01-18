package com.tyron.completion.xml.util;

import androidx.annotation.NonNull;

import java.io.File;

public class AndroidResourcesUtils {

    public static boolean isResourceXMLDir(File dir) {
        if (dir == null) {
            return false;
        }
        File parent = dir.getParentFile();
        if (parent != null) {
            return parent.getName().equals("res");
        }
        return false;
    }

    public static boolean isResourceXMLFile(@NonNull File file) {
        if (!file.getName().endsWith(".xml")) {
            return false;
        }
        return isResourceXMLDir(file.getParentFile());
    }

    public static boolean isLayoutXMLFile(@NonNull File file) {
        if (!file.getName().endsWith(".xml")) {
            return false;
        }

        if (file.getParentFile() != null) {
            File parent = file.getParentFile();
            if (parent.isDirectory() && parent.getName().startsWith("layout")) {
                return isResourceXMLFile(file);
            }
        }

        return false;
    }
}
