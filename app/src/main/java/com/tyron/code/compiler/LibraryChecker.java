package com.tyron.code.compiler;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tyron.code.model.Project;
import com.tyron.code.parser.FileManager;
import com.tyron.code.util.Decompress;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks libraries in app/libs and copies them to app/build/libs
 * This is done to support .aar files
 */
public class LibraryChecker {

    private static final String TAG = LibraryChecker.class.getSimpleName();

    private final Project mProject;
    private final File mLibsDir;

    public LibraryChecker(Project project) {
        mProject = project;
        mLibsDir = new File(project.getBuildDirectory(), "libs");
    }

    public void check() {
        File[] libs = mProject.getLibraryDirectory().listFiles();
        if (libs == null) {
            return;
        }

        List<String> files = new ArrayList<>();

        for (File lib : libs) {
            try {
                HashMap<String, String> map = new HashMap<>();
                if (lib.getName().endsWith(".jar")) {
                    copyIfNeeded(lib);
                    files.add(lib.getName().substring(0, lib.getName().lastIndexOf(".")));
                } else if (lib.getName().endsWith(".aar")) {
                    copyAar(lib);
                    files.add(lib.getName().substring(0, lib.getName().lastIndexOf(".")));
                }
            } catch (IOException e) {
                Log.e(TAG, e.getMessage());
            }
        }



        File[] libraries = mLibsDir.listFiles();
        if (libraries != null) {
            for (File library : libraries) {
                if (!files.contains(library.getName())) {
                    Log.d(TAG, "Library " + library.getName() + " has been removed.");
                    FileManager.deleteDir(library);
                }
            }
        }

    }

    private void copyIfNeeded(File file) throws IOException {
        String nameNoExt = file.getName().substring(0, file.getName().lastIndexOf("."));
        File check = new File(mLibsDir, nameNoExt + "/classes.jar");
        if (check.exists()) {
            if (check.length() == file.length()) {
                return;
            }
        }
        Log.d(TAG, "Copying jar file " + file.getName());
        check.getParentFile().mkdirs();
        check.createNewFile();
        check.createNewFile();
        copy(file, check);
    }

    private void copyAar(File aar) {
        String nameNoExt = aar.getName().substring(0, aar.getName().lastIndexOf("."));
        File check = new File(mLibsDir, nameNoExt);
        if (check.exists()) {
            check.delete();
        }

        Log.d(TAG, "Copying aar file " + aar.getName());
        check.mkdirs();
        Decompress.unzip(aar.getAbsolutePath(), check.getAbsolutePath());
    }


    public static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            try (OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
        }
    }

}
