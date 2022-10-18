package com.tyron.common;

import java.io.File;

public class ApplicationPaths {

    public static File getCacheDir() {
        return ApplicationProvider.getApplicationContext().getCacheDir();
    }
}
