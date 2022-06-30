package com.tyron.builder.groovy.scripts;

import static com.tyron.builder.internal.hash.Hashes.hashString;

import java.net.URI;

import static java.lang.Character.isJavaIdentifierPart;
import static java.lang.Character.isJavaIdentifierStart;
import static org.apache.commons.lang3.StringUtils.substringAfterLast;
import static org.apache.commons.lang3.StringUtils.substringBeforeLast;


import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.resource.ResourceLocation;
import com.tyron.builder.internal.resource.TextResource;

/**
 * A {@link ScriptSource} which loads the script from a URI.
 */
public class TextResourceScriptSource implements ScriptSource {
    private final TextResource resource;
    private String className;

    public TextResourceScriptSource(TextResource resource) {
        this.resource = resource;
    }

    @Override
    public TextResource getResource() {
        return resource;
    }

    @Override
    public String getFileName() {
        ResourceLocation location = resource.getLocation();
        if (location.getFile() != null) {
            return location.getFile().getPath();
        }
        if (location.getURI() != null) {
            return location.getURI().toString();
        }
        return getClassName();
    }

    @Override
    public String getDisplayName() {
        return getLongDisplayName().getDisplayName();
    }

    @Override
    public DisplayName getLongDisplayName() {
        return resource.getLongDisplayName();
    }

    @Override
    public DisplayName getShortDisplayName() {
        return resource.getShortDisplayName();
    }

    /**
     * Returns the class name for use for this script source.  The name is intended to be unique to support mapping
     * class names to source files even if many sources have the same file name (e.g. build.gradle).
     */
    @Override
    public String getClassName() {
        if (className == null) {
            this.className = initClassName();
        }
        return className;
    }

    private String initClassName() {
        URI sourceUri = getResource().getLocation().getURI();
        if (sourceUri != null) {
            String path = sourceUri.toString();
            return classNameFromPath(path);
        }

        return "script_" + Hashes.toCompactString(hashString(resource.getText()));
    }

    private String classNameFromPath(String path) {
        String name = substringBeforeLast(substringAfterLast(path, "/"), ".");

        StringBuilder className = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            className.append(
                isJavaIdentifierPart(ch) ? ch : '_');
        }
        if (className.length() > 0 && !isJavaIdentifierStart(className.charAt(0))) {
            className.insert(0, '_');
        }
        className.setLength(Math.min(className.length(), 30));
        className.append('_');
        className.append(Hashes.toCompactString(hashString(path)));

        return className.toString();
    }

}
