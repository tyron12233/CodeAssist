package com.tyron.resolver;

import com.android.tools.r8.v.b.P;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.parser.FileManager;
import com.tyron.resolver.exception.DuplicateDependencyException;
import com.tyron.resolver.model.Dependency;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.io.InputStream;
import java.net.URL;
import java.io.IOException;
import com.tyron.resolver.parser.POMParser;

import java.util.Collections;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.xmlpull.v1.XmlPullParserException;

import java.util.Map;
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

    /**
     * Adds a list of libraries into the resolved libraries list, useful when
     * the user has already downloaded some libraries and you only need to download
     * the new ones, the resolver will then skip these libraries or download a newer version if needed
     * @param resolved list of downloaded libraries
     */
    public void addResolvedLibraries(Set<Dependency> resolved) {
        for (Dependency dep : resolved) {
            mResolvedLibraries.put(dep, dep.getVersion());
        }
    }

    /**
     * Performs the resolution on the current thread
     */
    public Set<Dependency> resolveMain() {
        for (Dependency lib : library) {
            resolveDependency(lib);
        }
        return mResolvedLibraries.keySet();
    }

    /**
     *  The list of libraries that are already present in app/libs folder
     *  so we can check later if theres already a library present
     */
    private final Map<Dependency, String> mResolvedLibraries = new HashMap<>();

    private void resolveDependency(Dependency parent) {
        if (mResolvedLibraries.containsKey(parent)) {
            String resolvedVersion = mResolvedLibraries.get(parent);
            String thisVersion = parent.getVersion();

            int result = new ComparableVersion(resolvedVersion)
                    .compareTo(new ComparableVersion(thisVersion));

            if (result > 0) {
                // we have already resolved a version more recent than this one
                return;
            } else if (result == 0) {
                Log.d(TAG, "Skipping resolution of " + parent.getAtrifactId());
                return;
            } else {
                Log.d(TAG, "Found old version of library " + parent.getAtrifactId() + "\nold: " + resolvedVersion + "\nnew: " + thisVersion);
                mResolvedLibraries.remove(parent);
            }
        }

        File cache = getPomFromCache(parent);

        if (cache != null) {

            POMParser parser = new POMParser();
            try {
                for (Dependency dep : parser.parse(cache)) {
                    // skip test dependencies as its not important for now
                    if (dep.getScope() != null && dep.getScope().equals("test")) {
                        continue;
                    }
                    resolveDependency(dep);
                }
            } catch (IOException | XmlPullParserException e) {
                Log.d(TAG, "Failed to resolve " + parent + ", " + e.getMessage());
            }

            mResolvedLibraries.put(parent, parent.getVersion());
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

        mResolvedLibraries.put(parent, parent.getVersion());

        Log.d(TAG, "Resolved " + parent + " took: " + (System.currentTimeMillis() - start));

        try {
            is.close();
        } catch (IOException ignored) {}
    }

    /**
     * Retrieves pom file from the cache
     * @param dependency library to retrieve
     * @return the input stream of the file, null if its not found
     */
    private File getPomFromCache(Dependency dependency) {
        File pomCacheDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "pom");

        if (!pomCacheDir.exists()) {
            return null;
        }

        File file = new File(pomCacheDir, dependency.toString() + ".pom");

        if (!file.exists()) {
            return null;
        }

        return file;
    }

    private void savePomToCache(InputStream pom, Dependency dependency) throws IOException {
        File pomCacheDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "pom");

        if (!pomCacheDir.exists()) {
            if (!pomCacheDir.mkdir()) {
                throw new IOException("Failed to create cache directory for pom file");
            }
        }

        File file = new File(pomCacheDir, dependency.toString() + ".pom");
        if (!file.exists()) {
            if (!file.createNewFile()) {
                throw new IOException("Failed to create cache pom file " + file.getName());
            }
        }

        FileUtils.copyInputStreamToFile(pom, file);
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