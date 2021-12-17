package com.tyron.resolver.repository;

import androidx.annotation.Nullable;

import com.google.common.io.CharStreams;
import com.tyron.common.util.FileUtilsEx;
import com.tyron.resolver.model.Pom;
import com.tyron.resolver.parser.PomParser;

import org.apache.commons.io.FileUtils;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import kotlin.text.Charsets;

public class PomRepositoryImpl implements PomRepository {

    private File cacheDir;
    private final List<String> repositoryUrls;
    private final List<Pom> pomFiles;

    public PomRepositoryImpl() {
        this.repositoryUrls = new ArrayList<>();
        this.pomFiles = new ArrayList<>();
    }

    @Override
    @Nullable
    public Pom getPom(String declaration) {
        String[] pomNames = parsePomDeclaration(declaration);
        if (pomNames == null) {
            return null;
        }
        for (Pom pom : pomFiles) {
            if (!pomNames[0].equals(pom.getGroupId())) {
                continue;
            }
            if (!pomNames[1].equals(pom.getArtifactId())) {
                continue;
            }
            if (!pomNames[2].equals(pom.getVersionName())) {
                continue;
            }
            return pom;
        }
        return getPomFromUrls(pomNames);
    }

    private Pom getPomFromUrls(String[] names) {
        InputStream is = getFromUrls(getPathFromDeclaration(names) + ".pom");
        if (is != null) {
            String contents = null;
            try {
                contents = CharStreams.toString(new InputStreamReader(is));
                Pom parsed = new PomParser().parse(contents);
                parsed.setGroupId(names[0]);
                parsed.setArtifactId(names[1]);
                parsed.setVersionName(names[2]);
                pomFiles.add(parsed);
                savePomToCache(parsed, contents);
                return parsed;
            } catch (IOException | XmlPullParserException e) {
                // ignored
            }
        }
        return null;
    }

    private InputStream getFromUrls(String appendUrl) {
        for (int i = 0; i < repositoryUrls.size(); i++) {
            String url = repositoryUrls.get(i);
            try {
                URL downloadUrl = new URL(url + "/" + appendUrl);
                InputStream is = downloadUrl.openStream();
                if (is != null) {
                    return is;
                }
            } catch (IOException e) {
                if (i == repositoryUrls.size() - 1) {
                    // The dependency is not found on all urls, log
                    System.out.println("Dependency not found! " + appendUrl);
                }
            }
        }
        return null;
    }

    private void savePomToCache(Pom pom, String contents) throws IOException {
        File pomFile = new File(getPomCacheDirectory(), pom.getDeclarationString() + ".pom");
        if (!pomFile.exists() && !pomFile.createNewFile()) {
            throw new IOException("Unable to save pom file");
        }
        FileUtils.writeStringToFile(pomFile, contents, Charsets.UTF_8);
    }

    private String getPathFromDeclaration(String[] pomNames) {
        String groupId = pomNames[0].replace('.', '/');
        String artifactId = pomNames[1].replace('.','/');
        String path = groupId + "/" + artifactId + "/" + pomNames[2];
        return path + "/" + pomNames[1] + "-" + pomNames[2];
    }

    private String[] parsePomDeclaration(String declaration) {
        if (declaration.endsWith(".pom")) {
            declaration = declaration.substring(0, declaration.length() - 4);
        }
        String[] strings = declaration.split(":");
        if (strings.length < 3) {
            return null;
        }
        return strings;
    }

    @Override
    @Nullable
    public File getLibrary(Pom pom) throws IOException {
        if ("aar".equals(pom.getPackaging())) {
            return getFile(pom, ".aar");
        } else {
            return getFile(pom, ".jar");
        }
    }

    private File getFile(Pom pom, String extension) throws IOException {
        File[] files = getLibraryCacheDirectory().listFiles(c -> c.getName().equals(pom.getDeclarationString() + extension));
        if (files != null && files.length > 0) {
            return files[0];
        }
        InputStream is = getFromUrls(pom.getPath() + "/" + pom.getFileName() + extension);
        if (is != null) {
            File aarFile = new File(getLibraryCacheDirectory(), pom.getDeclarationString() + extension);
            FileUtilsEx.createFile(aarFile);
            FileUtils.copyInputStreamToFile(is, aarFile);
            return aarFile;
        }
        return null;
    }

    private File getPomCacheDirectory() {
        File pomCache = new File(cacheDir, "pom");
        if (!pomCache.exists() && !pomCache.mkdirs()) {
            // TODO: handle
        }
        return pomCache;
    }

    private File getLibraryCacheDirectory() {
        File libraryCache = new File(cacheDir, "library");
        if (!libraryCache.exists() && !libraryCache.mkdirs()) {
            // TODO: handle
        }
        return libraryCache;
    }

    @Override
    public void setCacheDirectory(File directory) {
        if (directory.isFile()) {
            throw new IllegalArgumentException("Argument should be a directory");
        }
        if (!directory.canRead() && !directory.canWrite()) {
            throw new IllegalArgumentException("Argument should be accessible");
        }
        this.cacheDir = directory;
    }

    @Override
    public void addRepositoryUrl(String url) {
        repositoryUrls.add(url);
    }

    @Override
    public void initialize() {
        if (cacheDir == null) {
            throw new IllegalStateException("Cache directory is not set.");
        }

        File[] pomFiles = getPomCacheDirectory().listFiles(c -> c.getName().endsWith(".pom"));
        if (pomFiles != null) {
            for (File pom : pomFiles) {
                String[] pomNames = parsePomDeclaration(pom.getName());
                if (pomNames == null) {
                    continue;
                }
                PomParser parser = new PomParser();
                try {
                    Pom parsed = parser.parse(pom);
                    parsed.setGroupId(pomNames[0]);
                    parsed.setArtifactId(pomNames[1]);
                    parsed.setVersionName(pomNames[2]);
                    this.pomFiles.add(parsed);
                } catch (XmlPullParserException | IOException e) {
                    // ignored
                    // TODO: should the file be deleted if its corrupt?
                }
            }
        }
    }
}
