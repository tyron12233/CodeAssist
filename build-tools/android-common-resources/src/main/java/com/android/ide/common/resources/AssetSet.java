package com.android.ide.common.resources;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.utils.ILogger;
import java.io.File;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Node;

/**
 * Represents a set of Assets.
 */
public class AssetSet extends DataSet<AssetItem, AssetFile> {
    /**
     * Creates an asset set with a given configName. The name is used to identify the set across
     * sessions.
     *
     * @param configName the name of the config this set is associated with
     * @param aaptEnv the value of "ANDROID_AAPT_IGNORE" environment variable
     */
    public AssetSet(@NonNull String configName, @Nullable String aaptEnv) {
        super(configName, true /*validateEnabled*/, aaptEnv);
    }

    @Override
    @NonNull
    protected DataSet<AssetItem, AssetFile> createSet(
            @NonNull String name, @Nullable String aaptEnv) {
        return new AssetSet(name, aaptEnv);
    }

    @Override
    protected AssetFile createFileAndItems(
            File sourceFolder, File file, ILogger logger, DocumentBuilderFactory factory) {
        // key is the relative path to the sourceFolder
        // e.g. foo/icon.png

        return new AssetFile(file, AssetItem.create(sourceFolder, file));
    }

    @Override
    protected AssetFile createFileAndItemsFromXml(@NonNull File file, @NonNull Node fileNode) {
        Attr nameAttr = (Attr) fileNode.getAttributes().getNamedItem(ATTR_NAME);
        if (nameAttr == null) {
            return null;
        }

        AssetItem item = new AssetItem(nameAttr.getValue());
        return new AssetFile(file, item);
    }

    @Override
    protected boolean isValidSourceFile(@NonNull File sourceFolder, @NonNull File file) {
        if (!super.isValidSourceFile(sourceFolder, file)) {
            return false;
        }

        // valid files are under the source folder, in any directory unless
        // the directory is excluded from packaging.
        File parent = file.getParentFile();
        while (parent != null && !parent.equals(sourceFolder)) {
            if (isIgnored(parent)) {
                return false;
            }
            parent = parent.getParentFile();
        }

        return parent != null;
    }

    @Override
    protected void readSourceFolder(
            @NonNull File sourceFolder, @NonNull ILogger logger, DocumentBuilderFactory factory)
            throws MergingException {
        readFiles(sourceFolder, sourceFolder, logger, factory);
    }

    private void readFiles(
            @NonNull File sourceFolder,
            @NonNull File folder,
            @NonNull ILogger logger,
            DocumentBuilderFactory factory)
            throws MergingException {
        File[] files = folder.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (!isIgnored(file)) {
                    if (file.isFile()) {
                        handleNewFile(sourceFolder, file, logger, factory);
                    } else if (file.isDirectory()) {
                        readFiles(sourceFolder, file, logger, factory);
                    }
                }
            }
        }
    }
}
