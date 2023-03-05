package com.android.ide.common.resources;

import static com.android.ide.common.resources.ResourceFile.ATTR_QUALIFIER;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.blame.Message;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.FolderTypeRelationship;
import com.android.resources.ResourceConstants;
import com.android.resources.ResourceFolderType;
import com.android.resources.ResourceType;
import com.android.utils.ILogger;
import com.android.utils.SdkUtils;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Implementation of {@link DataSet} for {@link ResourceMergerItem} and {@link ResourceFile}.
 *
 * <p>This is able to detect duplicates from the same source folders (same resource coming from the
 * values folder in same or different files).
 */
public class ResourceSet extends DataSet<ResourceMergerItem, ResourceFile> {
    public static final String ATTR_GENERATED_SET = "generated-set";
    public static final String ATTR_FROM_DEPENDENCY = "from-dependency";
    public static final String ATTR_AAPT_NAMESPACE = "aapt-namespace";

    private final String mLibraryName;

    @NonNull private final ResourceNamespace mNamespace;
    private ResourceSet mGeneratedSet;
    private ResourcePreprocessor mPreprocessor;

    /** Whether the resources come from a dependency or from the current subproject. */
    private boolean mIsFromDependency;

    private boolean mShouldParseResourceIds;
    private boolean mDontNormalizeQualifiers;
    private boolean mTrackSourcePositions = true;
    private boolean mCheckDuplicates = true;

    public ResourceSet(
            @NonNull String name,
            @NonNull ResourceNamespace namespace,
            String libraryName,
            boolean validateEnabled,
            @Nullable String aaptEnv) {
        super(name, validateEnabled, aaptEnv);
        mNamespace = namespace;
        mPreprocessor = NoOpResourcePreprocessor.INSTANCE;
        mLibraryName = libraryName;
    }

    public String getLibraryName() {
        return mLibraryName;
    }

    public void setGeneratedSet(ResourceSet generatedSet) {
        mGeneratedSet = generatedSet;
    }

    public void setPreprocessor(@NonNull ResourcePreprocessor preprocessor) {
        mPreprocessor = checkNotNull(preprocessor);
    }

    public void setShouldParseResourceIds(boolean shouldParse) {
        mShouldParseResourceIds = shouldParse;
    }

    public void setDontNormalizeQualifiers(boolean dontNormalizeQualifiers) {
        mDontNormalizeQualifiers = dontNormalizeQualifiers;
    }

    public void setTrackSourcePositions(boolean shouldTrack) {
        mTrackSourcePositions = shouldTrack;
    }

    /**
     * Tells the resource set whether to check for duplicate resource items or not.
     *
     * <p>Checking for duplicate items has to be turned off when loading resources of the Android
     * framework to avoid bogus errors. See, for example,
     * prebuilts/studio/layoutlib/data/res/values/attrs.xml.
     */
    public void setCheckDuplicates(boolean value) {
        mCheckDuplicates = value;
    }

    @NonNull
    @Override
    protected DataSet<ResourceMergerItem, ResourceFile> createSet(
            @NonNull String name, @Nullable String aaptEnv) {
        return new ResourceSet(name, mNamespace, mLibraryName, true, aaptEnv);
    }

    @Override
    protected ResourceFile createFileAndItems(
            File sourceFolder, File file, ILogger logger, DocumentBuilderFactory factory)
            throws MergingException {
        // get the type.
        FolderData folderData = getFolderData(file.getParentFile());

        if (folderData == null) {
            return null;
        }

        return createResourceFile(file, folderData, logger, factory);
    }

    @Override
    protected ResourceFile createFileAndItemsFromXml(@NonNull File file, @NonNull Node fileNode)
            throws MergingException {
        String qualifier =
                MoreObjects.firstNonNull(NodeUtils.getAttribute(fileNode, ATTR_QUALIFIER), "");
        String typeAttr = NodeUtils.getAttribute(fileNode, SdkConstants.ATTR_TYPE);
        FolderConfiguration folderConfiguration = FolderConfiguration.getConfigForQualifierString(qualifier);
        if (folderConfiguration == null) {
            return null;
        }

        if (NodeUtils.getAttribute(fileNode, SdkConstants.ATTR_PREPROCESSING) != null) {
            // FileType.GENERATED_FILES
            NodeList childNodes = fileNode.getChildNodes();
            int childCount = childNodes.getLength();

            List<ResourceMergerItem> resourceItems = Lists.newArrayListWithCapacity(childCount);

            for (int i = 0; i < childCount; i++) {
                Node childNode = childNodes.item(i);

                String path = NodeUtils.getAttribute(childNode, SdkConstants.ATTR_PATH);
                if (path == null) {
                    continue;
                }

                File generatedFile = new File(path);
                String resourceType = NodeUtils.getAttribute(childNode, SdkConstants.ATTR_TYPE);
                if (resourceType == null) {
                    continue;
                }
                String qualifers = NodeUtils.getAttribute(childNode, ATTR_QUALIFIER);
                if (qualifers == null) {
                    continue;
                }

                resourceItems.add(
                        new GeneratedResourceMergerItem(
                                getNameForFile(generatedFile),
                                mNamespace,
                                generatedFile,
                                FolderTypeRelationship.getRelatedResourceTypes(
                                                ResourceFolderType.getTypeByName(resourceType))
                                        .get(0),
                                qualifers,
                                mLibraryName));
            }

            return ResourceFile.generatedFiles(file, resourceItems, folderConfiguration);
        }
        else if (typeAttr == null) {
            // FileType.XML_VALUES
            List<ResourceMergerItem> resourceList = Lists.newArrayList();

            // loop on each node that represent a resource
            NodeList resNodes = fileNode.getChildNodes();
            int i = 0;

            // See if this is an "id generating" file, which means the first child is actually for the
            // file itself and the rest are for "@+id" resources defined inside.
            File parent = file.getParentFile();
            if (parent != null) {
                ResourceFolderType folderType = ResourceFolderType.getFolderType(parent.getName());
                if (folderType != ResourceFolderType.VALUES) {
                    if (resNodes.getLength() > 0) {
                        Node firstChild = resNodes.item(0);
                        String name = NodeUtils.getAttribute(firstChild, SdkConstants.ATTR_NAME);
                        String type = NodeUtils.getAttribute(firstChild, SdkConstants.ATTR_TYPE);
                        if (name != null && type != null) {
                            ResourceType resourceType = ResourceType.fromClassName(type);
                            if (resourceType != null) {
                                resourceList.add(
                                        new IdGeneratingResourceParser.IdResourceMergerItem(
                                                name, mNamespace, resourceType, mLibraryName));
                                i++;
                            }
                        }
                    }
                }
            }

            for (int nodesCount = resNodes.getLength(); i < nodesCount; i++) {
                Node resNode = resNodes.item(i);

                if (resNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }

                ResourceMergerItem r =
                        ValueResourceParser2.getResource(resNode, file, mNamespace, mLibraryName);
                if (r != null) {
                    resourceList.add(r);
                    if (r.getType() == ResourceType.STYLEABLE) {
                        // Need to also create ATTR items for its children
                        try {
                            ValueResourceParser2.addStyleableItems(
                                    resNode, resourceList, null, file, mNamespace, mLibraryName);
                        } catch (MergingException e) {
                            // since we are not passing a dup map, this will never be thrown
                            throw new AssertionError(null, e);
                        }
                    }
                }
            }

            return new ResourceFile(file, resourceList, folderConfiguration);
        } else {
            // single res file
            ResourceType type = ResourceType.fromClassName(typeAttr);
            if (type == null) {
                return null;
            }

            String nameAttr = NodeUtils.getAttribute(fileNode, ATTR_NAME);
            if (nameAttr == null) {
                return null;
            }

            if (getValidateEnabled()) {
                FileResourceNameValidator.validate(
                        file, ResourceFolderType.getFolderType(file.getParentFile().getName()));
            }
            ResourceMergerItem item =
                    new ResourceMergerItem(
                            nameAttr, mNamespace, type, null, mIsFromDependency, mLibraryName);
            return new ResourceFile(file, item, folderConfiguration);
        }
    }

    @Override
    protected void readSourceFolder(
            File sourceFolder, ILogger logger, DocumentBuilderFactory factory)
            throws MergingException {
        List<Message> errors = Lists.newArrayList();
        File[] folders = sourceFolder.listFiles();
        if (folders != null) {
            for (File folder : folders) {
                if (folder.isDirectory() && !isIgnored(folder)) {
                    FolderData folderData = getFolderData(folder);
                    if (folderData != null) {
                        try {
                            parseFolder(sourceFolder, folder, folderData, logger, factory);
                        } catch (MergingException e) {
                            errors.addAll(e.getMessages());
                        }
                    }
                }
            }
        }
        MergingException.throwIfNonEmpty(errors);
    }

    @Override
    protected boolean isValidSourceFile(@NonNull File sourceFolder, @NonNull File file) {
        if (!super.isValidSourceFile(sourceFolder, file)) {
            return false;
        }

        File resFolder = file.getParentFile();
        // valid files are right under a resource folder under the source folder
        return resFolder.getParentFile().equals(sourceFolder) &&
                !isIgnored(resFolder) &&
                ResourceFolderType.getFolderType(resFolder.getName()) != null;
    }

    @Nullable
    @Override
    protected ResourceFile handleNewFile(
            File sourceFolder, File file, ILogger logger, DocumentBuilderFactory factory)
            throws MergingException {
        ResourceFile resourceFile = createFileAndItems(sourceFolder, file, logger, factory);
        processNewResourceFile(sourceFolder, resourceFile);
        return resourceFile;
    }

    @Override
    protected boolean handleRemovedFile(File removedFile) {
        if (mGeneratedSet != null && mGeneratedSet.getDataFile(removedFile) != null) {
            return mGeneratedSet.handleRemovedFile(removedFile);
        } else {
            return super.handleRemovedFile(removedFile);
        }
    }

    @Override
    protected boolean handleChangedFile(
            @NonNull File sourceFolder,
            @NonNull File changedFile,
            @NonNull ILogger logger,
            @NonNull DocumentBuilderFactory factory)
            throws MergingException {
        FolderData folderData = getFolderData(changedFile.getParentFile());
        if (folderData == null) {
            return true;
        }

        ResourceFile resourceFile = getDataFile(changedFile);
        if (mGeneratedSet == null) {
            // This is a generated set.
            doHandleChangedFile(changedFile, resourceFile, factory);
            return true;
        }

        ResourceFile generatedSetResourceFile = mGeneratedSet.getDataFile(changedFile);
        boolean needsPreprocessing =
                !getResourceMergerItemsForGeneratedFilesIfNotFromDependency(changedFile).isEmpty();

        if (resourceFile != null && generatedSetResourceFile == null && needsPreprocessing) {
            // It didn't use to need preprocessing, but it does now.
            handleRemovedFile(changedFile);
            mGeneratedSet.handleNewFile(sourceFolder, changedFile, logger, factory);
        } else if (resourceFile == null
                   && generatedSetResourceFile != null
                   && !needsPreprocessing) {
            // It used to need preprocessing, but not anymore.
            mGeneratedSet.handleRemovedFile(changedFile);
            handleNewFile(sourceFolder, changedFile, logger, factory);
        } else if (resourceFile == null
                   && generatedSetResourceFile != null
                   && needsPreprocessing) {
            // Delegate to the generated set.
            mGeneratedSet.handleChangedFile(sourceFolder, changedFile, logger, factory);
        } else if (resourceFile != null
                   && !needsPreprocessing
                   && generatedSetResourceFile == null) {
            // The "normal" case, handle it here.
            doHandleChangedFile(changedFile, resourceFile, factory);
        } else {
            // Something strange happened.
            throw MergingException.withMessage("In DataSet '%s', no data file for changedFile. "
                                               + "This is an internal error in the incremental builds code; "
                                               + "to work around it, try doing a full clean build.",
                    getConfigName()).withFile(changedFile).build();
        }

        return true;
    }

    private void doHandleChangedFile(
            @NonNull File changedFile,
            ResourceFile resourceFile,
            @NonNull DocumentBuilderFactory factory)
            throws MergingException {
        switch (resourceFile.getType()) {
            case SINGLE_FILE:
                // single res file
                resourceFile.getItem().setTouched();
                break;
            case GENERATED_FILES:
                handleChangedItems(
                        resourceFile, getResourceMergerItemsForGeneratedFiles(changedFile));
                break;
            case XML_VALUES:
                // multi res. Need to parse the file and compare the items one by one.
                ValueResourceParser2 parser =
                        new ValueResourceParser2(changedFile, mNamespace, mLibraryName);
                parser.setTrackSourcePositions(mTrackSourcePositions);
                parser.setCheckDuplicates(mCheckDuplicates);

                List<ResourceMergerItem> parsedItems = parser.parseFile(factory);
                handleChangedItems(resourceFile, parsedItems);
                break;
            default:
                throw new IllegalStateException();
        }
    }

    private void handleChangedItems(
            ResourceFile resourceFile, List<ResourceMergerItem> currentItems)
            throws MergingException {
        Map<String, ResourceMergerItem> oldItems = Maps.newHashMap(resourceFile.getItemMap());
        Map<String, ResourceMergerItem> addedItems = Maps.newHashMap();

        // Set the source of newly determined items, so we can call getKey() on them.
        for (ResourceMergerItem currentItem : currentItems) {
            currentItem.setSourceFile(resourceFile);
        }

        for (ResourceMergerItem newItem : currentItems) {
            String newKey = newItem.getKey();
            ResourceMergerItem oldItem = oldItems.get(newKey);

            if (oldItem == null) {
                // this is a new item
                newItem.setTouched();
                addedItems.put(newKey, newItem);
            } else {
                // remove it from the list of oldItems (this is to detect deletion)
                //noinspection SuspiciousMethodCalls
                oldItems.remove(oldItem.getKey());

                if (oldItem.getSourceFile().getType() == DataFile.FileType.XML_VALUES) {
                    if (!oldItem.compareValueWith(newItem)) {
                        // if the values are different, take the values from the newItem
                        // and update the old item status.

                        oldItem.setValue(newItem);
                    }
                } else {
                    oldItem.setTouched();
                }
            }
        }

        // at this point oldItems is left with the deleted items.
        // just update their status to removed.
        for (ResourceMergerItem deletedItem : oldItems.values()) {
            deletedItem.setRemoved();
        }

        // Now we need to add the new items to the resource file and the main map
        for (Map.Entry<String, ResourceMergerItem> entry : addedItems.entrySet()) {
            // Clear the item from the old file so it can be added to the new one.
            entry.getValue().setSourceFile(null);
            addItem(entry.getValue(), entry.getKey());
        }

        resourceFile.addItems(addedItems.values());
    }

    /**
     * Reads the content of a typed resource folder (sub folder to the root of res folder), and
     * loads the resources from it.
     *
     * @param sourceFolder the main res folder
     * @param folder the folder to read.
     * @param folderData the folder Data
     * @param logger a logger object
     * @throws MergingException if something goes wrong
     */
    private void parseFolder(
            File sourceFolder,
            File folder,
            FolderData folderData,
            ILogger logger,
            DocumentBuilderFactory factory)
            throws MergingException {
        File[] files = folder.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (!file.isFile() || isIgnored(file)) {
                    continue;
                }

                ResourceFile resourceFile = createResourceFile(file, folderData, logger, factory);
                processNewResourceFile(sourceFolder, resourceFile);
            }
        }
    }

    private void processNewResourceFile(File sourceFolder, ResourceFile resourceFile)
            throws MergingException {
        if (resourceFile != null) {
            if (resourceFile.getType() == DataFile.FileType.GENERATED_FILES
                    && mGeneratedSet != null) {
                mGeneratedSet.processNewDataFile(sourceFolder, resourceFile, true);
            } else {
                processNewDataFile(sourceFolder, resourceFile, true /*setTouched*/);
            }
        }
    }

    private ResourceFile createResourceFile(
            @NonNull File file,
            @NonNull FolderData folderData,
            @NonNull ILogger logger,
            @NonNull DocumentBuilderFactory factory)
            throws MergingException {
        if (getValidateEnabled()) {
            FileResourceNameValidator.validate(file, folderData.folderType);
        }

        if (folderData.type != null) {
            List<ResourceMergerItem> generatedFiles =
                    getResourceMergerItemsForGeneratedFilesIfNotFromDependency(file);
            if (!generatedFiles.isEmpty()) {
                return ResourceFile.generatedFiles(
                        file,
                        generatedFiles,
                        folderData.folderConfiguration);
            } else if (mShouldParseResourceIds && folderData.isIdGenerating &&
                       SdkUtils.endsWithIgnoreCase(file.getPath(), SdkConstants.DOT_XML)) {
                String resourceName = getNameForFile(file);
                List<ResourceMergerItem> items;

                if (resourceName.isEmpty()) {
                    // This is only possible if validation is disabled, like when running from the IDE.
                    items = Collections.emptyList();
                } else {
                    IdGeneratingResourceParser parser =
                            new IdGeneratingResourceParser(
                                    file, resourceName, folderData.type, mNamespace, mLibraryName);
                    Collection<ResourceMergerItem> idItems = parser.getIdResourceMergerItems();
                    ResourceMergerItem fileItem = parser.getFileResourceMergerItem();
                    items = new ArrayList<>(idItems.size() + 1);
                    items.add(fileItem);
                    items.addAll(idItems);
                }
                return new ResourceFile(file, items, folderData.folderConfiguration);
            } else {
                return new ResourceFile(
                        file,
                        new ResourceMergerItem(
                                getNameForFile(file),
                                mNamespace,
                                folderData.type,
                                null,
                                mIsFromDependency,
                                mLibraryName),
                        folderData.folderConfiguration);
            }
        } else {
            try {
                ValueResourceParser2 parser =
                        new ValueResourceParser2(file, mNamespace, mLibraryName);
                parser.setTrackSourcePositions(mTrackSourcePositions);
                parser.setCheckDuplicates(mCheckDuplicates);
                List<ResourceMergerItem> items = parser.parseFile(factory);

                return new ResourceFile(file, items, folderData.folderConfiguration);
            } catch (MergingException e) {
                logger.error(e, "Failed to parse %s", file.getAbsolutePath());
                throw e;
            }
        }
    }

    @NonNull
    private List<ResourceMergerItem> getResourceMergerItemsForGeneratedFilesIfNotFromDependency(
            @NonNull File file) throws MergingException {
        if (mIsFromDependency) {
            return Collections.emptyList();
        }
        return getResourceMergerItemsForGeneratedFiles(file);
    }

    @NonNull
    private List<ResourceMergerItem> getResourceMergerItemsForGeneratedFiles(@NonNull File file)
            throws MergingException {
        Collection<File> filesToBeGenerated;
        try {
            filesToBeGenerated = mPreprocessor.getFilesToBeGenerated(file);
        } catch (IOException e) {
            throw new MergingException(e);
        }

        List<ResourceMergerItem> resourceItems = new ArrayList<>(filesToBeGenerated.size());
        for (File generatedFile : filesToBeGenerated) {
            FolderData generatedFileFolderData =
                    getFolderData(generatedFile.getParentFile());

            checkState(
                    generatedFileFolderData != null,
                    "Can't determine folder type for %s",
                    generatedFile.getPath());

            resourceItems.add(
                    new GeneratedResourceMergerItem(
                            getNameForFile(generatedFile),
                            mNamespace,
                            generatedFile,
                            generatedFileFolderData.type,
                            generatedFileFolderData.folderConfiguration.getQualifierString(),
                            mLibraryName));
        }
        return resourceItems;
    }

    @NonNull
    private static String getNameForFile(@NonNull File file) {
        String name = file.getName();
        int pos = name.indexOf('.'); // get the resource name based on the filename
        if (pos >= 0) {
            name = name.substring(0, pos);
        }
        return name;
    }

    public boolean isFromDependency() {
        return mIsFromDependency;
    }

    public void setFromDependency(boolean fromDependency) {
        mIsFromDependency = fromDependency;
    }

    /**
     * temp structure containing a qualifier string and a {@link com.android.resources.ResourceType}.
     */
    private static class FolderData {
        FolderConfiguration folderConfiguration = new FolderConfiguration();
        ResourceType type = null;
        ResourceFolderType folderType = null;
        boolean isIdGenerating = false;
    }

    /**
     * Returns a FolderData for the given folder.
     *
     * @param folder the folder.
     * @return the FolderData object, or null if we can't determine the {#link ResourceFolderType}
     *         of the folder.
     */
    @Nullable
    private FolderData getFolderData(File folder) throws MergingException {
        FolderData fd = new FolderData();

        String folderName = folder.getName();
        int pos = folderName.indexOf(ResourceConstants.RES_QUALIFIER_SEP);
        if (pos != -1) {
            fd.folderType = ResourceFolderType.getTypeByName(folderName.substring(0, pos));
            if (fd.folderType == null) {
                return null;
            }

            FolderConfiguration folderConfiguration = FolderConfiguration.getConfigForFolder(folderName);
            if (folderConfiguration == null) {
                throw MergingException.withMessage("Invalid resource directory name")
                        .withFile(folder).build();
            }

            // normalize it
            if (!mDontNormalizeQualifiers) {
                folderConfiguration.normalizeByAddingImpliedVersionQualifier();
            }

            fd.folderConfiguration = folderConfiguration;
        } else {
            fd.folderType = ResourceFolderType.getTypeByName(folderName);
        }

        if (fd.folderType != null && fd.folderType != ResourceFolderType.VALUES) {
            fd.type = FolderTypeRelationship.getNonIdRelatedResourceType(fd.folderType);
            fd.isIdGenerating = FolderTypeRelationship.isIdGeneratingFolderType(fd.folderType);
        }
        return fd;
    }

    @Override
    void appendToXml(
            @NonNull Node setNode,
            @NonNull Document document,
            @NonNull MergeConsumer<ResourceMergerItem> consumer,
            boolean includeTimestamps) {
        if (mGeneratedSet != null) {
            NodeUtils.addAttribute(
                    document,
                    setNode,
                    null,
                    ATTR_GENERATED_SET,
                    mGeneratedSet.getConfigName());
        }

        if (mIsFromDependency) {
            NodeUtils.addAttribute(
                    document,
                    setNode,
                    null,
                    ATTR_FROM_DEPENDENCY,
                    SdkConstants.VALUE_TRUE);
        }

        NodeUtils.addAttribute(
                document, setNode, null, ATTR_AAPT_NAMESPACE, mNamespace.getXmlNamespaceUri());

        super.appendToXml(setNode, document, consumer, includeTimestamps);
    }

    @NonNull
    public ResourceNamespace getNamespace() {
        return mNamespace;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        ResourceSet that = (ResourceSet) o;
        return mIsFromDependency == that.mIsFromDependency
                && mShouldParseResourceIds == that.mShouldParseResourceIds
                && mDontNormalizeQualifiers == that.mDontNormalizeQualifiers
                && mTrackSourcePositions == that.mTrackSourcePositions
                && Objects.equals(mNamespace, that.mNamespace)
                && Objects.equals(mLibraryName, that.mLibraryName)
                && Objects.equals(mGeneratedSet, that.mGeneratedSet)
                && Objects.equals(mPreprocessor, that.mPreprocessor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                mLibraryName,
                mGeneratedSet,
                mPreprocessor,
                mIsFromDependency,
                mNamespace,
                mShouldParseResourceIds,
                mDontNormalizeQualifiers,
                mTrackSourcePositions);
    }

}
