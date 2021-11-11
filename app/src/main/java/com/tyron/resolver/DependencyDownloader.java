package com.tyron.resolver;

import android.util.Log;

import com.tyron.code.ApplicationLoader;
import com.tyron.resolver.model.Dependency;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public class DependencyDownloader {

    public interface Listener {
        void onDownload(Dependency dependency);
    }

    private static final String TAG = DependencyDownloader.class.getSimpleName();

    private File mOutputDir;
    private Set<Dependency> mDownloadedLibraries;
    private Listener mListener;

    public DependencyDownloader(Set <Dependency> downloaded, File file) {
        mOutputDir = file;
        mDownloadedLibraries = downloaded;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }


    public void download(Set<Dependency> libraries) throws IOException {
        if (!mOutputDir.exists()) {
            if (!mOutputDir.mkdirs()) {
                throw new IOException("Unable to create library output directory");
            }
        }
        for (Dependency dependency : libraries) {
            download(dependency);
        }
    }

    public void cache(Set<Dependency> libraries) throws IOException {
        for (Dependency dependency : libraries) {
            downloadToCache(dependency);
        }
    }

    private void downloadToCache(Dependency library) throws IOException {
        if (mListener != null) {
            mListener.onDownload(library);
        }

        File cachedLibrary = getFromCache(library);
        if (cachedLibrary != null) {
            return;
        }

        // if we got into here then we download the library
        // lets try with aar first
        boolean isAar = true;
        InputStream is = DependencyResolver.getAarStream(library);
        if (is == null) {
            // this library doesnt have an aar file, lets try with a jar file
            isAar = false;
            is = DependencyResolver.getJarStream(library);
        }

        if (is == null) {
            Log.d(TAG, "Failed to find download link for library: " + library);
            return;
        }

        Log.d(TAG, "Downloading library: " + library.getFileName());
        saveToCache(library, is, isAar);

        is.close();
    }

    private void download(Dependency library) throws IOException {
        if (mListener != null) {
            mListener.onDownload(library);
        }

        // first we check if the library exists or there is an older version of it
        for (Dependency downloaded : mDownloadedLibraries) {
            if (!downloaded.getGroupId().equals(library.getGroupId())) {
                continue;
            }

            if (!downloaded.getAtrifactId().equals(library.getAtrifactId())) {
                continue;
            }



            ComparableVersion downloadedVersion = new ComparableVersion(downloaded.getVersion());
            ComparableVersion willDownloadVersion = new ComparableVersion(library.getVersion());

            int result = downloadedVersion.compareTo(willDownloadVersion);
            if (result == 0) {
                // The versions are equal, so don't bother downloading this one
                return;
            }

            // the version is explicitly defined by the user, delete the newer library and
            // download this one
            if (library.isUserDefined()) {
                File file = new File(mOutputDir, downloaded.toString() + ".jar");
                if (!file.exists()) {
                    // lets try if this is an aar
                    file = new File(mOutputDir, downloaded.toString() + ".aar");
                }

                if (file.exists()) {
                    if (!file.delete()) {
                        throw new IOException("Failed to delete library: " + file.getName());
                    }
                }

                Log.d(TAG, "Overriding newer library " + downloaded.toString());
                break;
            }

            if (result > 0) {
                // the downloaded version is greater than this one, so keep the downloaded version
                return;
            } else {
                // This version is newer, delete the old one and proceed to download
                File file = new File(mOutputDir, downloaded.toString() + ".jar");
                if (!file.exists()) {
                   // lets try if this is an aar
                   file = new File(mOutputDir, downloaded.toString() + ".aar");
                }

                if (file.exists()) {
                    if (!file.delete()) {
                        throw new IOException("Failed to delete old library: " + file.getName());
                    }
                }
                break;
            }
        }

        // check the cache first if we have downloaded this library before
        File cachedLibrary = getFromCache(library);
        if (cachedLibrary != null) {
            Log.d(TAG, "Retrieved cached library " + cachedLibrary.getName());
            // there is a cache for it, don't proceed on download

            File outputFile = new File(mOutputDir, cachedLibrary.getName());
            if (!outputFile.exists()) {
                if (!outputFile.createNewFile()) {
                    throw new IOException("Unable to create file: " + outputFile.getName());
                }
            }
            FileUtils.copyFile(cachedLibrary, outputFile);
            return;
        }

        // if we got into here then we download the library
        // lets try with aar first
        boolean isAar = true;
        InputStream is = DependencyResolver.getAarStream(library);
        if (is == null) {
            // this library doesnt have an aar file, lets try with a jar file
            isAar = false;
            is = DependencyResolver.getJarStream(library);
        }

        if (is == null) {
            Log.d(TAG, "Failed to find download link for library: " + library);
            return;
        }

        Log.d(TAG, "Downloading library: " + library.getFileName());

        File outputFile = new File(mOutputDir, library.toString() + (isAar ? ".aar" : ".jar"));
        if (!outputFile.exists()) {
            if (!outputFile.createNewFile()) {
                throw new IOException("Unable to create file: " + outputFile.getName());
            }
        }
        FileUtils.copyInputStreamToFile(is, outputFile);

        // after downloading, copy the library to our cache so we wont dowload it again on a new project
        saveToCache(outputFile);
    }

    /**
     * Checks if we have downloaded the library before
     * @param dependency library to download
     * @return null if it doesn't exist on the cache otherwise it returns the file
     */
    private File getFromCache(Dependency dependency) {
        File libraryCacheDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "libraries");
        if (!libraryCacheDir.exists()) {
            return null;
        }

        File libraryToCheck = new File(libraryCacheDir, dependency.toString() + ".aar");
        if (!libraryToCheck.exists()) {
            libraryToCheck = new File(libraryCacheDir, dependency.toString() + ".jar");
        }

        if (!libraryToCheck.exists()) {
            return null;
        }

        return libraryToCheck;
    }

    private void saveToCache(File library) throws IOException {
        if (!library.exists()) {
            return;
        }

        File libraryCacheDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "libraries");
        if (!libraryCacheDir.exists()) {
            if (!libraryCacheDir.mkdir()) {
                throw new IOException("Unable to create library cache directory");
            }
        }

        File libraryFile = new File(libraryCacheDir, library.getName());
        if (!libraryFile.exists()) {
            if (!libraryFile.createNewFile()) {
                throw new IOException("Unable to create library cache file for " + libraryFile.getName());
            }
        }

        Log.d(TAG, "Saving library " + library + " to cache");
        FileUtils.copyFile(library, libraryFile);
    }

    private void saveToCache(Dependency library, InputStream inputStream, boolean isAar) throws IOException {
        File libraryCacheDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "libraries");
        if (!libraryCacheDir.exists()) {
            if (!libraryCacheDir.mkdir()) {
                throw new IOException("Unable to create library cache directory");
            }
        }

        File libraryFile = new File(libraryCacheDir, library.getFileName() + (isAar ? ".aar" : ".jar"));
        if (!libraryFile.exists()) {
            if (!libraryFile.createNewFile()) {
                throw new IOException("Unable to create library cache file for " + libraryFile.getName());
            }
        }

        FileUtils.copyInputStreamToFile(inputStream, libraryFile);
    }
}
