package com.tyron.resolver;

import com.tyron.resolver.exception.DuplicateDependencyException;
import com.tyron.resolver.model.Dependency;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.io.InputStream;
import java.net.URL;
import java.io.IOException;
import com.tyron.resolver.parser.POMParser;

import java.util.Collections;

import org.apache.commons.io.FileUtils;
import org.xmlpull.v1.XmlPullParserException;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class DependencyResolver {

    private static final String TAG = DependencyResolver.class.getSimpleName();
    private static final List<String> REPOS = Arrays.asList(
            "https://repo1.maven.org/maven2",
            "https://maven.google.com"
    );
    private final File mOutputDir;
    private final ExecutorService service = Executors.newFixedThreadPool(4);
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final Set<Dependency> library;

    public DependencyResolver(Dependency library, File file) {
        this(Collections.singleton(library), file);
    }

    public DependencyResolver(Set<Dependency> library, File file) {
        this.library = library;
        mOutputDir = file;
    }

    public Set<Dependency> resolveMain() {
        for (Dependency lib : library) {
            resolveDependency(lib);
        }
        return resolvedDependencies;
    }
    /**
     *  The list of libraries that are already present in app/libs folder
     *  so we can check later if theres already a library present
     */
    private Set<Dependency> resolvedDependencies = new HashSet<>();

    private void resolveDependency(Dependency parent) {
        if (resolvedDependencies.contains(parent)) {
            Log.d(TAG, "Skipping " + parent);
            return;
        }

        InputStream is = getInputStream(parent);

        if (is == null) {
            return;
        }

        long start = System.currentTimeMillis();

        POMParser parser = new POMParser();
        try {
            for (Dependency dep : parser.parse(is)) {
                // skip test dependencies as its not important for now
                if (dep.getScope() != null && dep.getScope().equals("test")) {
                    continue;
                }
                resolveDependency(dep);
            }
        } catch (IOException | XmlPullParserException e) {
            Log.d(TAG, "Failed to resolve " + parent + ", " + e.getMessage());
        }

        resolvedDependencies.add(parent);

        Log.d(TAG, "Resolved " + parent + " took: " + (start - System.currentTimeMillis()));

        try {
            is.close();
        } catch (IOException e) {}
    }


    public static InputStream getJarStream(Dependency dep) {
        for (String url : REPOS) {
            try {
                URL downloadUrl = new URL(url + "/" + dep.getJarDownloadLink());
                InputStream is = downloadUrl.openStream();

                if (is != null) {
                    return is;
                }
            } catch (IOException ignore) {

            }
        }
        return null;
    }

    public static InputStream getAarStream(Dependency dep) {
        for (String url: REPOS) {
            try {
                URL downloadUrl = new URL(url + "/" + dep.getAarDownloadLink());
                InputStream is = downloadUrl.openStream();

                if (is != null) {
                    return is;
                }
            } catch (IOException ignore) {

            }
        }
        return null;
    }

    public static InputStream getInputStream(Dependency dependency) {
        String pomPath = dependency.getPomDownloadLink();

        for (String url : REPOS) {
            try {
                URL downloadUrl = new URL(url + "/" + pomPath);

                InputStream is = downloadUrl.openStream();
                if (is != null) {
                    return is;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }
}