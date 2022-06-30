package com.tyron.builder.internal.scripts;

import static java.util.Collections.emptyList;

import com.tyron.builder.scripts.ScriptingLanguage;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DefaultScriptFileResolver implements ScriptFileResolver {

    private static final String[] EXTENSIONS = scriptingLanguageExtensions();

    @Override
    public File resolveScriptFile(File dir, String basename) {
        for (String extension : EXTENSIONS) {
            File candidate = new File(dir, basename + extension);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public List<File> findScriptsIn(File dir) {
        File[] candidates = dir.listFiles();
        if (candidates == null || candidates.length == 0) {
            return emptyList();
        }
        List<File> found = new ArrayList<>(candidates.length);
        for (File candidate : candidates) {
            if (candidate.isFile() && hasScriptExtension(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    private boolean hasScriptExtension(File file) {
        for (String extension : EXTENSIONS) {
            if (extension.equals(FilenameUtils.getExtension(file.getName()))) {
                return true;
            }
        }
        return false;
    }

    private static String[] scriptingLanguageExtensions() {
        List<ScriptingLanguage> scriptingLanguages = ScriptingLanguages.all();
        String[] extensions = new String[scriptingLanguages.size()];
        for (int i = 0; i < extensions.length; i++) {
            extensions[i] = scriptingLanguages.get(i).getExtension();
        }
        return extensions;
    }
}