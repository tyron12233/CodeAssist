package com.android.ide.common.resources;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.utils.ILogger;
import java.io.File;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * A {@link ResourceSet} that contains only generated files (e.g. PNGs generated from a vector
 * drawable XML). It is always a mirror of a normal {@link ResourceSet} which delegates to this
 * set when it encounters a file that needs to be replaced by generated files.
 */
public class GeneratedResourceSet extends ResourceSet {
    public static final String ATTR_GENERATED = "generated";

    public GeneratedResourceSet(ResourceSet originalSet, @Nullable String aaptEnv) {
        super(
                originalSet.getConfigName() + "$Generated",
                originalSet.getNamespace(),
                originalSet.getLibraryName(),
                originalSet.getValidateEnabled(),
                aaptEnv);
        for (File source : originalSet.getSourceFiles()) {
            addSource(source);
        }
    }

    public GeneratedResourceSet(
            String name,
            ResourceNamespace namespace,
            String libraryName,
            @Nullable String aaptEnv) {
        super(name, namespace, libraryName, true, aaptEnv);
    }

    @Override
    @NonNull
    protected DataSet<ResourceMergerItem, ResourceFile> createSet(
            @NonNull String name, @Nullable String aaptEnv) {
        return new GeneratedResourceSet(name, ResourceNamespace.TODO(), getLibraryName(), aaptEnv);
    }

    @Override
    void appendToXml(
            @NonNull Node setNode,
            @NonNull Document document,
            @NonNull MergeConsumer<ResourceMergerItem> consumer,
            boolean includeTimestamps) {
        NodeUtils.addAttribute(document, setNode, null, ATTR_GENERATED, SdkConstants.VALUE_TRUE);
        super.appendToXml(setNode, document, consumer, includeTimestamps);
    }

    @Override
    public void loadFromFiles(ILogger logger, DocumentBuilderFactory factory)
            throws MergingException {
        // Do nothing, the original set will hand us the generated files.
    }

    @Override
    public File findMatchingSourceFile(File file) {
        // Do nothing, the original set will hand us the generated files.
        return null;
    }
}
