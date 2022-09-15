package com.tyron.completion.xml.v2.project;

import static com.android.SdkConstants.ANDROID_NS_NAME_PREFIX;
import static com.android.SdkConstants.ATTR_FORMAT;
import static com.android.SdkConstants.ATTR_NAME;
import static com.android.SdkConstants.DOT_9PNG;
import static com.android.SdkConstants.NEW_ID_PREFIX;
import static com.android.SdkConstants.TAG_RESOURCES;
import static com.android.SdkConstants.TOOLS_URI;
import static com.android.ide.common.util.PathStringUtil.toPathString;
import static com.android.resources.ResourceFolderType.VALUES;
import static com.android.utils.TraceUtils.getSimpleId;
import static com.tyron.completion.xml.v2.project.ResourceUpdateTracer.pathForLogging;
import static org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil.isAncestor;

import androidx.annotation.GuardedBy;

import com.android.ide.common.rendering.api.DensityBasedResourceValue;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceVisitor;
import com.android.ide.common.resources.ValueResourceNameValidator;
import com.android.ide.common.resources.configuration.DensityQualifier;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.util.PathString;
import com.android.resources.Density;
import com.android.resources.FolderTypeRelationship;
import com.android.resources.ResourceFolderType;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.utils.SdkUtils;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.xml.v2.base.BasicFileResourceItem;
import com.tyron.completion.xml.v2.base.BasicResourceItem;
import com.tyron.completion.xml.v2.base.BasicValueResourceItemBase;
import com.tyron.completion.xml.v2.base.LoadableResourceRepository;
import com.tyron.completion.xml.v2.base.RepositoryConfiguration;
import com.tyron.completion.xml.v2.base.RepositoryLoader;
import com.tyron.completion.xml.v2.base.ResourceSourceFile;
import com.tyron.completion.xml.v2.events.XmlReparsedEvent;
import com.tyron.completion.xml.v2.events.XmlResourceChangeEvent;
import com.tyron.xml.completion.util.DOMUtils;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMParser;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.lang.model.SourceVersion;

import kotlin.io.FilesKt;


public final class ResourceFolderRepository extends LocalResourceRepository implements LoadableResourceRepository {

    @NotNull
    private final ConcurrentMap<File, ResourceItemSource<?>> mySources = new ConcurrentHashMap<>();
    @SuppressWarnings("InstanceGuardedByStatic")
    @GuardedBy("ITEM_MAP_LOCK")
    @NotNull
    private final Map<ResourceType, ListMultimap<String, ResourceItem>> myResourceTable =
            new EnumMap<>(ResourceType.class);
    private static final Comparator<ResourceItemSource<?>> SOURCE_COMPARATOR =
            Comparator.comparing(ResourceItemSource::getFolderConfiguration);

    @NotNull
    private final AndroidModule myFacet;
    @NotNull
    private final File myResourceDir;
    @NotNull
    private final ResourceNamespace myNamespace;
    private int myNumXmlFilesLoadedInitially;
    private int myNumXmlFilesLoadedInitiallyFromSources;

    /**
     * Common prefix of paths of all file resources.  Used to compose resource paths returned by
     * the {@link BasicFileResourceItem#getSource()} method.
     */
    @NotNull
    private final String myResourcePathPrefix;
    /**
     * Same as {@link #myResourcePathPrefix} but in a form of {@link PathString}.  Used to produce
     * resource paths returned by the {@link BasicFileResourceItem#getOriginalSource()} method.
     */
    @NotNull
    private final PathString myResourcePathBase;

    static ResourceFolderRepository create(@NotNull AndroidModule facet,
                                           @NotNull File dir,
                                           @NotNull ResourceNamespace namespace,
                                           @Nullable ResourceFolderRepositoryCachingData cachingData) {
        return new ResourceFolderRepository(facet, dir, namespace, cachingData);
    }

    private ResourceFolderRepository(@NotNull AndroidModule facet,
                                     @NotNull File resourceDir,
                                     @NotNull ResourceNamespace namespace,
                                     @Nullable ResourceFolderRepositoryCachingData cachingData) {
        super(facet.getName());
        myFacet = facet;
        myResourceDir = resourceDir;
        myNamespace = namespace;
        myResourcePathPrefix = RepositoryLoader.portableFileName(myResourceDir.getPath()) + '/';
        myResourcePathBase = new PathString(myResourcePathPrefix);

        Loader loader = new Loader(this, cachingData);
        loader.load();

        facet.getProject()
                .getEventManager()
                .subscribeEvent(XmlResourceChangeEvent.class, (event, unsubscribe) -> {
                    scan(event.getFile(), event.getNewContent());
                });
    }

    private static void addToResult(@NotNull ResourceItem item,
                                    @NotNull Map<ResourceType,
                                            ListMultimap<String, ResourceItem>> result) {
        // The insertion order matters, see AppResourceRepositoryTest.testStringOrder.
        result.computeIfAbsent(item.getType(), t -> LinkedListMultimap.create())
                .put(item.getName(), item);
    }

    /**
     * Inserts the given resources into this repository, while holding the global repository lock.
     */
    private void commitToRepository(@NotNull Map<ResourceType,
            ListMultimap<String, ResourceItem>> itemsByType) {
        if (!itemsByType.isEmpty()) {
            synchronized (ITEM_MAP_LOCK) {
                commitToRepositoryWithoutLock(itemsByType);
            }
        }
    }

    /**
     * Inserts the given resources into this repository without acquiring any locks. Safe to call
     * only while
     * holding {@link #ITEM_MAP_LOCK} or during construction of ResourceFolderRepository.
     */
    @SuppressWarnings("GuardedBy")
    private void commitToRepositoryWithoutLock(@NotNull Map<ResourceType, ListMultimap<String,
            ResourceItem>> itemsByType) {
        for (Map.Entry<ResourceType, ListMultimap<String, ResourceItem>> entry :
                itemsByType.entrySet()) {
            ListMultimap<String, ResourceItem> map = getOrCreateMap(entry.getKey());
            map.putAll(entry.getValue());
        }
    }

    @SuppressWarnings("InstanceGuardedByStatic")
    @GuardedBy("ITEM_MAP_LOCK")
    @NotNull
    private ListMultimap<String, ResourceItem> getOrCreateMap(@NotNull ResourceType type) {
        // Use LinkedListMultimap to preserve ordering for editors that show original order.
        return myResourceTable.computeIfAbsent(type, k -> LinkedListMultimap.create());
    }

    public File getResourceDir() {
        return myResourceDir;
    }

    @Override
    public @NotNull Path getOrigin() {
        return Paths.get(myResourceDir.getPath());
    }

    @Override
    public @Nullable String getLibraryName() {
        return null;
    }

    @Override
    @NotNull
    public String getResourceUrl(@NotNull String relativeResourcePath) {
        return myResourcePathPrefix + relativeResourcePath;
    }

    @Override
    public @NotNull PathString getSourceFile(@NotNull String relativeResourcePath,
                                             boolean forFileResource) {
        return myResourcePathBase.resolve(relativeResourcePath);
    }

    @Override
    public boolean containsUserDefinedResources() {
        return true;
    }

    @Override
    public ResourceNamespace getNamespace() {
        return myNamespace;
    }

    @Override
    public String getPackageName() {
        return myNamespace.getPackageName() !=
               null ? myNamespace.getPackageName() : myFacet.getPackageName();
    }

    @Override
    protected @Nullable ListMultimap<String, ResourceItem> getMap(@NotNull ResourceNamespace namespace,
                                                                  @NotNull ResourceType type) {
        if (!namespace.equals(myNamespace)) {
            return null;
        }
        return myResourceTable.get(type);
    }

    @Override
    protected @NotNull Set<File> computeResourceDirs() {
        return Collections.singleton(myResourceDir);
    }

    @Override
    public ResourceVisitor.VisitResult accept(ResourceVisitor visitor) {
        if (visitor.shouldVisitNamespace(myNamespace)) {
            synchronized (ITEM_MAP_LOCK) {
                if (acceptByResources(myResourceTable, visitor) ==
                    ResourceVisitor.VisitResult.ABORT) {
                    return ResourceVisitor.VisitResult.ABORT;
                }
            }
        }

        return ResourceVisitor.VisitResult.CONTINUE;
    }

    private boolean checkResourceFilename(@NotNull PathString file,
                                          @NotNull ResourceFolderType folderType) {
        return SourceVersion.isIdentifier(fileNameToResourceName(file.getFileName()));
    }

    /**
     * Returns the resource name that a file with the given {@code fileName} declares.
     *
     * <p>The returned string is not guaranteed to be a valid resource name, it should be checked by
     * {@link com.android.ide.common.resources.FileResourceNameValidator} before being used. If the
     * resource type is known, it's preferable to validate the full filename (including extension)
     * first.
     */
    public static String fileNameToResourceName(String fileName) {
        int lastExtension = fileName.lastIndexOf('.');
        if (lastExtension <= 0) {
            return fileName;
        }

        if (fileName.endsWith(DOT_9PNG)) {
            if (fileName.length() > DOT_9PNG.length()) {
                return fileName.substring(0, fileName.length() - DOT_9PNG.length());
            } else {
                return fileName;
            }
        }

        return fileName.substring(0, lastExtension);
    }

    private void scan(@NotNull File file, CharSequence content) {
        ResourceFolderType folderType = IdeResourcesUtil.getFolderType(file);
        if (folderType == null || !isResourceFile(file)) {
            return;
        }

        if (!file.exists()) {
            removeResourcesContainedInFileOrDirectory(file);
            return;
        }

        if (content == null && file.getName().endsWith(".xml")) {
            content = FilesKt.readText(file, StandardCharsets.UTF_8);
        }
        scan(file, content, folderType);
    }

    private void removeResourcesContainedInFileOrDirectory(@NotNull File file) {
        ResourceUpdateTracer.log(() -> getSimpleId(this) +
                                       ".processRemovalOfFileOrDirectory " +
                                       pathForLogging(file));
        if (file.isDirectory()) {
            for (var iterator = mySources.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                iterator.remove();
                File sourceFile = entry.getKey();
                if (isAncestor(file, sourceFile, true)) {
                    ResourceItemSource<?> source = entry.getValue();
                    removeSource(sourceFile, source);
                }
            }
        } else {
            ResourceItemSource<?> source = mySources.remove(file);
            if (source != null) {
                removeSource(file, source);
            }
        }
    }

    private void removeSource(@NotNull File file, @NotNull ResourceItemSource<?> source) {
        ResourceUpdateTracer.log(() -> getSimpleId(this) +
                                       ".onSourceRemoved " +
                                       pathForLogging(file));

        boolean removed = removeItemsFromSource(source);
        if (removed) {
            setModificationCount(ourModificationCounter.incrementAndGet());
            invalidateParentCaches(this, ResourceType.values());
        }

        ResourceFolderType folderType = IdeResourcesUtil.getFolderType(file);
        if (folderType != null) {
            clearLayoutlibCaches(file, folderType);
        }
    }

    private void clearLayoutlibCaches(@NotNull File file, @NotNull ResourceFolderType folderType) {

    }

    /**
     * Removes all resource items associated the given source file.
     *
     * @return true if any resource items were removed from the repository
     */
    private boolean removeItemsFromSource(@NotNull ResourceItemSource<?> source) {
        boolean changed = false;

        synchronized (ITEM_MAP_LOCK) {
            for (ResourceItem item : source) {
                ListMultimap<String, ResourceItem> map = myResourceTable.get(item.getType());
                if (map == null) {
                    continue;
                }
                List<ResourceItem> items = map.get(item.getName());
                for (Iterator<ResourceItem> iter = items.iterator(); iter.hasNext(); ) {
                    ResourceItem candidate = iter.next();
                    if (candidate == item) {
                        iter.remove();
                        changed = true;
                        break;
                    }
                }
                if (items.isEmpty()) {
                    map.removeAll(item.getName());
                }
            }
        }
        return changed;
    }


    private void scan(@NotNull File file,
                      CharSequence content,
                      @NotNull ResourceFolderType folderType) {
        if (!isResourceFile(file)) {
            return;
        }
        Map<ResourceType, ListMultimap<String, ResourceItem>> result = new HashMap<>();

        System.out.println("Scanning " + file.getName());

        if (folderType == VALUES) {

            // First delete out the previous items.
            ResourceItemSource<?> source = mySources.remove(file);
            boolean removed = false;
            if (source != null) {
                removed = removeItemsFromSource(source);
            }

            boolean added = false;
            File parentFile = file.getParentFile();
            assert parentFile != null;
            FolderConfiguration folderConfiguration =
                    FolderConfiguration.getConfigForFolder(parentFile.getName());
            if (folderConfiguration != null) {
                added = scanValueFileAsPsi(result, file, content, folderConfiguration);
            }

            if (added || removed) {
                // TODO: Consider doing a deeper diff of the changes to the resource items
                //       to determine if the removed and added items actually differ.
                setModificationCount(ourModificationCounter.incrementAndGet());
                invalidateParentCaches(this, ResourceType.values());
            }
        } else if (checkResourceFilename(toPathString(file), folderType)) {
            ResourceItemSource<?> source = mySources.get(file);
            if (source instanceof DomResourceFile) {
                // If the old file was a DomResourceFile for an XML file, we can update ID
                // ResourceItems in place.
                DomResourceFile domResourceFile = ((DomResourceFile) source);
                // Already seen this file; no need to do anything unless it's an XML file with
                // generated ids;
                // in that case we may need to update the id's.
                if (FolderTypeRelationship.isIdGeneratingFolderType(folderType)) {

                    // We've already seen this resource, so no change in the ResourceItem for the
                    // file itself (e.g. @layout/foo from layout-land/foo.xml). However, we may have
                    // to update the id's:
                    Set<String> idsBefore = new HashSet<>();
                    synchronized (ITEM_MAP_LOCK) {
                        ListMultimap<String, ResourceItem> idMultimap =
                                myResourceTable.get(ResourceType.ID);
                        if (idMultimap != null) {
                            List<DomResourceItem> idItems = new ArrayList<>();
                            for (DomResourceItem item : domResourceFile) {
                                if (item.getType() == ResourceType.ID) {
                                    idsBefore.add(item.getName());
                                    idItems.add(item);
                                }
                            }
                            for (String id : idsBefore) {
                                // TODO(sprigogin): Simplify this code since the following
                                //  comment is out of date.
                                //  Note that ResourceFile has a flat map (not a multimap) so it
                                //  doesn't
                                //  record all items (unlike the myItems map) so we need to
                                //  remove the map
                                //  items manually, can't just do map.remove(item.getName(), item)
                                List<ResourceItem> mapItems = idMultimap.get(id);
                                if (!mapItems.isEmpty()) {
                                    List<ResourceItem> toDelete = new ArrayList<>(mapItems.size());
                                    for (ResourceItem mapItem : mapItems) {
                                        if (mapItem instanceof DomResourceItem &&
                                            ((DomResourceItem) mapItem).getSourceFile() ==
                                            domResourceFile) {
                                            toDelete.add(mapItem);
                                        }
                                    }
                                    for (ResourceItem item : toDelete) {
                                        idMultimap.remove(item.getName(), item);
                                    }
                                }
                            }
                            for (DomResourceItem item : idItems) {
                                domResourceFile.removeItem(item);
                            }
                        }
                    }

                    // Add items for this file.
                    List<DomResourceItem> idItems = new ArrayList<>();
                    ProgressManager.checkCanceled();
                    addIds(file, content, idItems, result);

                    if (!idItems.isEmpty()) {
                        for (DomResourceItem item : idItems) {
                            domResourceFile.addItem(item);
                        }
                    }

                    // Identities may have changed even if the ids are the same, so update maps.
                    setModificationCount(ourModificationCounter.incrementAndGet());
                    invalidateParentCaches(this, ResourceType.ID);
                }
            } else {
                // Either we're switching to PSI or the file is not XML (image or font), which is
                // not incremental.
                // Remove old items first, rescan below to add back, but with a possibly
                // different multimap list order.

                if (source != null) {
                    removeItemsFromSource(source);
                }

                ResourceType type = FolderTypeRelationship.getNonIdRelatedResourceType(folderType);
                boolean idGeneratingFolder =
                        FolderTypeRelationship.isIdGeneratingFolderType(folderType);

                ProgressManager.checkCanceled();
                clearLayoutlibCaches(file, folderType);

                File parentFile = file.getParentFile();
                if (parentFile != null) {
                    FolderConfiguration folderConfiguration =
                            FolderConfiguration.getConfigForFolder(parentFile.getName());
                    if (folderConfiguration != null) {
                        ProgressManager.checkCanceled();
                        scanFileResourceFileAsPsi(file,
                                content,
                                folderType,
                                folderConfiguration,
                                type,
                                idGeneratingFolder,
                                result);
                    }
                }
                setModificationCount(ourModificationCounter.incrementAndGet());
                invalidateParentCaches(this, ResourceType.values());
            }
        }

        myFacet.getProject().getEventManager().dispatchEvent(new XmlReparsedEvent(file));
        commitToRepository(result);
    }


    private void scanFileResourceFileAsPsi(@NotNull File file,
                                           CharSequence content,
                                           @NotNull ResourceFolderType folderType,
                                           @NotNull FolderConfiguration folderConfiguration,
                                           @NotNull ResourceType type,
                                           boolean idGenerating,
                                           @NotNull Map<ResourceType, ListMultimap<String,
                                                   ResourceItem>> result) {
        // XML or image.
        String resourceName = SdkUtils.fileNameToResourceName(file.getName());
        if (!checkResourceFilename(toPathString(file), folderType)
            || content == null
            || !file.getName().endsWith(".xml")
        ) {
            return; // Not a valid file resource name.
        }

        RepositoryConfiguration configuration =
                new RepositoryConfiguration(this, folderConfiguration);
        DomResourceItem item = DomResourceItem.forFile(resourceName, type, this, file);

        if (idGenerating) {
            List<DomResourceItem> items = new ArrayList<>();
            items.add(item);
            addToResult(item, result);
            addIds(file, content, items, result);

            DomResourceFile resourceFile =
                    new DomResourceFile(file, items, folderType, configuration);
            mySources.put(file, resourceFile);
        } else {
            DomResourceFile resourceFile = new DomResourceFile(file,
                    Collections.singletonList(item),
                    folderType,
                    configuration);
            mySources.put(file, resourceFile);
            addToResult(item, result);
        }
    }

    private boolean scanValueFileAsPsi(@NotNull Map<ResourceType, ListMultimap<String,
            ResourceItem>> result,
                                       @NotNull File file,
                                       CharSequence content,
                                       @NotNull FolderConfiguration folderConfiguration) {
        boolean added = false;
        DOMDocument domDocument = DOMParser.getInstance().parse(content.toString(), "", null);

        System.out.println("Parsed XML File: " + file.getName());

        DOMElement rootElement = DOMUtils.getRootElement(domDocument);
        if (rootElement == null) {
            return false;
        }
        if (!rootElement.getLocalName().equals(TAG_RESOURCES)) {
            return false;
        }
        List<DOMElement> subTags = DOMUtils.getSubTags(rootElement);
        List<DomResourceItem> items = new ArrayList<>(subTags.size());
        for (DOMElement tag : subTags) {
            String name = tag.getAttribute(ATTR_NAME);
            ResourceType type = IdeResourcesUtil.getResourceTypeForResourceTag(tag);
            if (type != null && isValidValueResourceName(name)) {
                DomResourceItem item = DomResourceItem.forXmlTag(name, type, this, tag, file);
                addToResult(item, result);
                items.add(item);
                added = true;

                if (type == ResourceType.STYLEABLE) {
                    // For styleables we also need to create attr items for its children.
                    List<DOMElement> attrs = DOMUtils.getSubTags(tag);
                    for (DOMElement child : attrs) {
                        String attrName = child.getAttribute(ATTR_NAME);
                        if (isValidValueResourceName(attrName) &&
                            !attrName.startsWith(ANDROID_NS_NAME_PREFIX)
                            // Only add attr nodes for elements that specify a format or have
                            // flag/enum children; otherwise
                            // it's just a reference to an existing attr.
                            &&
                            (child.getAttribute(ATTR_FORMAT) != null ||
                             DOMUtils.getSubTags(child).size() > 0)) {
                            DomResourceItem attrItem = DomResourceItem.forXmlTag(attrName,
                                    ResourceType.ATTR,
                                    this,
                                    child,
                                    file);
                            items.add(attrItem);
                            addToResult(attrItem, result);
                        }
                    }
                }

                DomResourceFile resourceFile = new DomResourceFile(file,
                        items,
                        VALUES,
                        new RepositoryConfiguration(this, folderConfiguration));
                mySources.put(file, resourceFile);
            }
        }
        return added;
    }

    private void addIds(@NotNull File file,
                        @NotNull CharSequence content,
                        @NotNull List<DomResourceItem> items,
                        @NotNull Map<ResourceType, ListMultimap<String, ResourceItem>> result) {
        DOMDocument element = DOMParser.getInstance().parse(content.toString(), "", null);
//        if (element instanceof DOMElement) {
//            addIds((DOMElement)element, items, result);
//        }

        List<DOMElement> tags = DOMUtils.findChildrenOfType(element, DOMElement.class);
        for (DOMElement tag : tags) {
            if (tag == null) {
                continue;
            }
            addIds(file, tag, items, result);
        }
    }

    /**
     * If the attribute value has the form "@+id/<i>name</i>" and the <i>name</i> part is a valid
     * resource name, returns it. Otherwise, returns null.
     */
    @Nullable
    private String createIdNameFromAttribute(@NotNull DOMAttr attribute) {
        String attributeValue = StringUtil.notNullize(attribute.getValue()).trim();
        if (attributeValue.startsWith(NEW_ID_PREFIX) &&
            !TOOLS_URI.equals(DOMUtils.getNamespace(attribute))) {
            String id = attributeValue.substring(NEW_ID_PREFIX.length());
            if (isValidValueResourceName(id)) {
                return id;
            }
        }
        return null;
    }


    private void addIds(@NotNull File file,
                        @NotNull DOMElement tag,
                        @NotNull List<DomResourceItem> items,
                        @NotNull Map<ResourceType, ListMultimap<String, ResourceItem>> result) {

        for (DOMAttr attribute : tag.getAttributeNodes()) {
            String id = createIdNameFromAttribute(attribute);
            if (id != null) {
                DomResourceItem item = DomResourceItem.forXmlTag(id,
                        ResourceType.ID,
                        this,
                        attribute.getParentElement(),
                        file);
                items.add(item);
                addToResult(item, result);
            }
        }
    }

    @Contract(value = "null -> false")
    private static boolean isValidValueResourceName(@Nullable String name) {
        return !StringUtil.isEmpty(name) &&
               ValueResourceNameValidator.getErrorText(name, null) == null;
    }

    /**
     * Returns true if the given element represents a resource folder
     * (e.g. res/values-en-rUS or layout-land, *not* the root res/ folder).
     */
    private boolean isResourceFolder(@NotNull File virtualFile) {
        if (virtualFile.isDirectory()) {
            File parentDirectory = virtualFile.getParentFile();
            if (parentDirectory != null) {
                return parentDirectory.equals(myResourceDir);
            }
        }
        return false;
    }

    private boolean isResourceFile(@NotNull File virtualFile) {
        File parent = virtualFile.getParentFile();
        return parent != null && isResourceFolder(parent);
    }

    /**
     * Tracks state used by the initial scan, which may be used to save the state to a cache.
     * The file cache omits non-XML single-file items, since those are easily derived from the
     * file path.
     */
    private static class Loader extends RepositoryLoader<ResourceFolderRepository> {

        private final ResourceFolderRepository myRepository;
        private final File myResourceDir;

        @NotNull
        private final Map<ResourceType, ListMultimap<String, ResourceItem>> myResources =
                new EnumMap<>(ResourceType.class);
        @NotNull
        private final Map<File, ResourceItemSource<BasicResourceItem>> mySources = new HashMap<>();
        @NotNull
        private final Map<File, BasicFileResourceItem> myFileResources = new HashMap<>();

        // The following two fields are used as a cache of size one for quick conversion from a
        // PathString to a VirtualFile.
        @Nullable
        private File myLastVirtualFile;
        @Nullable
        private PathString myLastPathString;

        @NotNull Set<File> myFilesToReparseAsPsi = new HashSet<>();
        private static final Logger LOG = Logger.getInstance(ResourceFolderRepository.class);

        Loader(@NotNull ResourceFolderRepository repository,
               @Nullable ResourceFolderRepositoryCachingData cachingData) {
            super(repository.myResourceDir.toPath(), null, repository.getNamespace());
            myRepository = repository;
            myResourceDir = repository.myResourceDir;
            myDefaultVisibility = ResourceVisibility.UNDEFINED;
        }

        public void load() {
            if (!myResourceDir.exists()) {
                return;
            }

            scanResFolder();

            populateRepository();

            scanQueuedPsiResources();
        }

        /**
         * For resource files that failed when scanning with a VirtualFile, retries with PsiFile.
         */
        private void scanQueuedPsiResources() {
            for (File file : myFilesToReparseAsPsi) {
                myRepository.scan(file, FilesKt.readText(file, StandardCharsets.UTF_8));
            }
        }

        private void scanResFolder() {
            try {
                File[] files = myResourceDir.listFiles();
                if (files == null) {
                    return;
                }
                for (File subDir : files) {
                    String folderName = subDir.getName();
                    FolderInfo folderInfo = FolderInfo.create(folderName, myFolderConfigCache);
                    if (folderInfo != null) {
                        RepositoryConfiguration configuration =
                                getConfiguration(myRepository, folderInfo.configuration);
                        for (File file : Objects.requireNonNull(subDir.listFiles())) {
                            if (file.getName().startsWith(".")) {
                                continue; // Skip file with the name starting with a dot.
                            }

                            if (folderInfo.folderType ==
                                VALUES ? mySources.containsKey(file) : myFileResources.containsKey(
                                    file)) {
                                if (isParsableFile(file, folderInfo)) {
                                    countCacheHit();
                                }
                                continue;
                            }

                            PathString pathString = toPathString(file);
                            myLastVirtualFile = file;
                            myLastPathString = pathString;
                            try {
                                loadResourceFile(pathString, folderInfo, configuration);
                                if (isParsableFile(file, folderInfo)) {
                                    countCacheMiss();
                                }
                            } catch (ParsingException e) {
                                // Reparse the file as PSI. The PSI parser is more forgiving than
                                // KXmlParser because
                                // it is designed to work with potentially malformed files in the
                                // middle of editing.
                                myFilesToReparseAsPsi.add(file);
                            }
                        }
                    }
                }
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                LOG.error("Failed to load resources from " + myResourceDirectoryOrFile, e);
            }

            super.finishLoading(myRepository);

            // Associate file resources with sources.
            for (Map.Entry<File, BasicFileResourceItem> entry : myFileResources.entrySet()) {
                File virtualFile = entry.getKey();
                BasicFileResourceItem item = entry.getValue();
                ResourceItemSource<BasicResourceItem> source =
                        mySources.computeIfAbsent(virtualFile,
                                file -> new VfsResourceFile(file,
                                        item.getRepositoryConfiguration()));
                source.addItem(item);
            }

            // Populate the myResources map.
            List<ResourceItemSource<BasicResourceItem>> sortedSources =
                    new ArrayList<>(mySources.values());
            // Sort sources according to folder configurations to have deterministic ordering of
            // resource items in myResources.
            sortedSources.sort(SOURCE_COMPARATOR);
            for (ResourceItemSource<BasicResourceItem> source : sortedSources) {
                for (ResourceItem item : source) {
                    getOrCreateMap(item.getType()).put(item.getName(), item);
                }
            }
        }


        private void loadResourceFile(@NotNull PathString file,
                                      @NotNull FolderInfo folderInfo,
                                      @NotNull RepositoryConfiguration configuration) {
            if (folderInfo.resourceType == null) {
                if (isXmlFile(file)) {
                    parseValueResourceFile(file, configuration);
                }
            } else if (myRepository.checkResourceFilename(file, folderInfo.folderType)) {
                if (isXmlFile(file) && folderInfo.isIdGenerating) {
                    parseIdGeneratingResourceFile(file, configuration);
                }

                BasicFileResourceItem item = createFileResourceItem(file,
                        folderInfo.resourceType,
                        configuration,
                        folderInfo.isIdGenerating);
                addResourceItem(item, (ResourceFolderRepository) item.getRepository());
            }
        }

        @Override
        @NotNull
        protected ResourceSourceFile createResourceSourceFile(@NotNull PathString file,
                                                              @NotNull RepositoryConfiguration configuration) {
            return new VfsResourceFile(file.toFile(), configuration);
        }


        @NotNull
        private BasicFileResourceItem createFileResourceItem(@NotNull PathString file,
                                                             @NotNull ResourceType resourceType,
                                                             @NotNull RepositoryConfiguration configuration,
                                                             boolean idGenerating) {
            String resourceName = SdkUtils.fileNameToResourceName(file.getFileName());
            ResourceVisibility visibility = getVisibility(resourceType, resourceName);
            Density density = null;
            if (DensityBasedResourceValue.isDensityBasedResourceType(resourceType)) {
                DensityQualifier densityQualifier =
                        configuration.getFolderConfiguration().getDensityQualifier();
                if (densityQualifier != null) {
                    density = densityQualifier.getValue();
                }
            }
            return createFileResourceItem(file,
                    resourceType,
                    resourceName,
                    configuration,
                    visibility,
                    density,
                    idGenerating);
        }


        @NotNull
        private BasicFileResourceItem createFileResourceItem(@NotNull PathString file,
                                                             @NotNull ResourceType type,
                                                             @NotNull String name,
                                                             @NotNull RepositoryConfiguration configuration,
                                                             @NotNull ResourceVisibility visibility,
                                                             @Nullable Density density,
                                                             boolean idGenerating) {
            if (!idGenerating) {
                return super.createFileResourceItem(file,
                        type,
                        name,
                        configuration,
                        visibility,
                        density);
            }
            File virtualFile = file.toFile();
            String relativePath = getResRelativePath(file);
            return density == null ? new VfsFileResourceItem(type,
                    name,
                    configuration,
                    visibility,
                    relativePath,
                    virtualFile) : new VfsDensityBasedFileResourceItem(type,
                    name,
                    configuration,
                    visibility,
                    relativePath,
                    virtualFile,
                    density);
        }

        private static boolean isParsableFile(@NotNull File file, @NotNull FolderInfo folderInfo) {
            return (folderInfo.folderType == VALUES || folderInfo.isIdGenerating) &&
                   isXmlFile(file.getName());
        }

        @NotNull
        private ListMultimap<String, ResourceItem> getOrCreateMap(@NotNull ResourceType resourceType) {
            return myResources.computeIfAbsent(resourceType, type -> LinkedListMultimap.create());
        }

        @Override
        protected void handleParsingError(@NotNull PathString file, @NotNull Exception e) {
            throw new ParsingException(e);
        }

        @Override
        @NotNull
        protected InputStream getInputStream(@NotNull PathString file) throws IOException {
            return new FileInputStream(file.toFile());
        }

        private void populateRepository() {
            myRepository.mySources.putAll(mySources);
            myRepository.commitToRepositoryWithoutLock(myResources);
        }

        @Override
        protected void addResourceItem(@NotNull BasicResourceItem item,
                                       @NotNull ResourceFolderRepository repository) {
            if (item instanceof BasicValueResourceItemBase) {
                VfsResourceFile sourceFile =
                        (VfsResourceFile) ((BasicValueResourceItemBase) item).getSourceFile();
                File virtualFile = sourceFile.getVirtualFile();
                if (virtualFile != null && virtualFile.exists() && !virtualFile.isDirectory()) {
                    sourceFile.addItem(item);
                    mySources.put(virtualFile, sourceFile);
                }
            } else if (item instanceof VfsFileResourceItem) {
                VfsFileResourceItem fileResourceItem = (VfsFileResourceItem) item;
                File virtualFile = fileResourceItem.getVirtualFile();
                if (virtualFile != null && virtualFile.exists() && !virtualFile.isDirectory()) {
                    myFileResources.put(virtualFile, fileResourceItem);
                }
            } else if (item instanceof BasicFileResourceItem) {
                BasicFileResourceItem fileResourceItem = (BasicFileResourceItem) item;
                File file = fileResourceItem.getSource().toFile();
                if (file != null && file.exists() && !file.isDirectory()) {
                    myFileResources.put(file, fileResourceItem);
                }
            } else {
                throw new IllegalArgumentException("Unexpected type: " + item.getClass().getName());
            }
        }

        private void countCacheHit() {
            ++myRepository.myNumXmlFilesLoadedInitially;
        }

        private void countCacheMiss() {
            ++myRepository.myNumXmlFilesLoadedInitially;
            ++myRepository.myNumXmlFilesLoadedInitiallyFromSources;
        }
    }

    private static class ParsingException extends RuntimeException {
        ParsingException(Throwable cause) {
            super(cause);
        }
    }
}
