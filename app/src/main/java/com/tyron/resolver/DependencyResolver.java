package com.tyron.resolver;

import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.common.io.Closeables;
import com.tyron.code.ApplicationLoader;
import com.tyron.resolver.model.Dependency;
import com.tyron.resolver.parser.POMParser;

import org.apache.commons.io.FileUtils;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyResolver {

    public interface Listener {
        void onResolve(Dependency dependency);
    }

    private static final String TAG = DependencyResolver.class.getSimpleName();
    private static final List<String> REPOS = Arrays.asList(
            "https://repo1.maven.org/maven2",
            "https://maven.google.com",
            "https://jitpack.io",
            "https://jcenter.bintray.com/"
    );
    private final File mOutputDir;
    private File mPomCacheDir;
    private Listener mListener;
    private final Set<Dependency> library;

    public DependencyResolver(Dependency library, File file) {
        this(Collections.singleton(library), file);
    }

    public DependencyResolver(Set<Dependency> library, File file) {
        this.library = library;
        mOutputDir = file;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Adds a list of libraries into the resolved libraries list, useful when
     * the user has already downloaded some libraries and you only need to download
     * the new ones, the resolver will then skip these libraries or download a newer version if needed
     *
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
     * The list of libraries that are already present in app/libs folder
     * so we can check later if theres already a library present
     */
    private final Map<Dependency, String> mResolvedLibraries = new HashMap<>();

    private void resolveDependency(Dependency parent) {
        if (mListener != null) {
            mListener.onResolve(parent);
        }
        if (mResolvedLibraries.containsKey(parent)) {
            String resolvedVersion = mResolvedLibraries.get(parent);
            String thisVersion = parent.getVersion();

            try {
                int result = new ComparableVersion(resolvedVersion)
                        .compareTo(new ComparableVersion(thisVersion));

                if (result == 0) {
                    Log.d(TAG, "Skipping resolution of " + parent.getAtrifactId());
                    return;
                }

                if (parent.isUserDefined()) {
                    mResolvedLibraries.remove(parent);
                } else {
                    if (result > 0) {
                        // we have already resolved a version more recent than this one
                        return;
                    } else {
                        Log.d(TAG, "Found old version of library " + parent.getAtrifactId() + "\nold: " + resolvedVersion + "\nnew: " + thisVersion);
                        mResolvedLibraries.remove(parent);
                    }
                }
            } catch (Throwable ignore) {
                // Keep both
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

        String contents = null;
        try {
            contents = CharStreams.toString(new InputStreamReader(is, Charsets.UTF_8));
        } catch (IOException ignore) {

        }
        Closeables.closeQuietly(is);

        POMParser parser = new POMParser();
        try {
            for (Dependency dep : parser.parse(contents)) {
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
        try {
            saveToCache(contents, parent);
        } catch (IOException e) {
            Log.w(TAG, "Unable to save dependency to cache: ", e);
        }

        Log.d(TAG, "Resolved " + parent + " took: " + (System.currentTimeMillis() - start));
    }

    private void saveToCache(String contents, Dependency dependency) throws IOException {
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

        FileUtils.writeStringToFile(file, contents, Charsets.UTF_8);
    }

    /**
     * Retrieves pom file from the cache
     *
     * @param dependency library to retrieve
     * @return the input stream of the file, null if its not found
     */
    private File getPomFromCache(Dependency dependency) {
        File pomCacheDir = getPomCacheDir();

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
        File pomCacheDir = getPomCacheDir();
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

    private File getPomCacheDir() {
        if (mPomCacheDir == null) {
            return new File(ApplicationLoader.applicationContext.getCacheDir(), "pom");
        }
        return mPomCacheDir;

    }

    @VisibleForTesting
    public void setPomCacheDir(File dir) {
        mPomCacheDir = dir;
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
        for (String url : REPOS) {
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
                Exception e = ignored;
            }
        }
        return null;
    }
}