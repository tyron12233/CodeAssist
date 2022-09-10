package com.android.ide.common.resources;

import static com.android.SdkConstants.ATTR_NAME;
import static com.android.SdkConstants.ATTR_TYPE;
import static com.android.SdkConstants.DOT_XML;
import static com.android.SdkConstants.RES_QUALIFIER_SEP;
import static com.android.SdkConstants.TAG_RESOURCES;
import static com.google.common.base.Preconditions.checkState;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.blame.MergingLog;
import com.android.ide.common.blame.SourceFile;
import com.android.ide.common.blame.SourceFilePosition;
import com.android.ide.common.blame.SourcePosition;
import com.android.ide.common.workers.ExecutorServiceAdapter;
import com.android.ide.common.workers.WorkerExecutorFacade;
import com.android.resources.ResourceFolderType;
import com.android.resources.ResourceType;
import com.android.utils.FileUtils;
import com.android.utils.XmlUtils;
import com.google.common.base.Charsets;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import javax.inject.Inject;
import org.openjdk.javax.xml.parsers.DocumentBuilder;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * A {@link MergeWriter} for assets, using {@link ResourceMergerItem}. Also takes care of compiling
 * resources and stripping data binding from layout files.
 */
public class MergedResourceWriter
        extends MergeWriter<ResourceMergerItem, MergedResourceWriter.FileGenerationParameters> {

    @Nullable
    private MergingLog mMergingLog;

    private DocumentBuilderFactory mFactory;

    /** Map of XML values files to write after parsing all the files. the key is the qualifier. */
    private ListMultimap<String, ResourceMergerItem> mValuesResMap;

    /**
     * Set of qualifier that had a previously written resource now gone. This is to keep a list of
     * values files that must be written out even with no touched or updated resources, in case one
     * or more resources were removed.
     */
    private Set<String> mQualifierWithDeletedValues;

    /**
     * Futures we are waiting for...
     */
    @NonNull
    private final ConcurrentLinkedDeque<Future<File>> mCompiling;

    /**
     * File where {@link #mCompiledFileMap} is read from and where its contents are written.
     */
    @NonNull
    private final File mCompiledFileMapFile;

    /**
     * Maps resource files to their compiled files. Used to compiled resources that no longer
     * exist.
     */
    private final Properties mCompiledFileMap;

    private final MergedResourceWriterRequest mergeWriterRequest;

    @NonNull
    private final ConcurrentLinkedQueue<CompileResourceRequest> mCompileResourceRequests =
            new ConcurrentLinkedQueue<>();

    /**
     * A {@link MergeWriter} for resources, using {@link ResourceMergerItem}. Also takes care of
     * compiling resources and stripping data binding from layout files.
     *
     * @param request a MergedResourceWriterRequest containing constants for merge logging.
     */
    public MergedResourceWriter(MergedResourceWriterRequest request) {
        super(request.getRootFolder(), request.getWorkerExecutor());
        mergeWriterRequest = request;
        mMergingLog = request.getBlameLog();
        mCompiling = new ConcurrentLinkedDeque<>();
        mCompiledFileMapFile =
                new File(mergeWriterRequest.getTemporaryDirectory(), "compile-file-map.properties");
        mCompiledFileMap = new Properties();
        if (mCompiledFileMapFile.exists()) {
            try (FileReader fr = new FileReader(mCompiledFileMapFile)) {
                mCompiledFileMap.load(fr);
            } catch (IOException e) {
                /*
                 * If we can't load the map, then we proceed without one. This means that
                 * we won't be able to delete compiled resource files if the original ones
                 * are deleted.
                 */
            }
        }
    }

    /** Used in tools/idea. */
    @SuppressWarnings("unused")
    public static MergedResourceWriter createWriterWithoutPngCruncher(
            @NonNull File rootFolder,
            @Nullable File publicFile,
            @Nullable File blameLogFolder,
            @NonNull ResourcePreprocessor preprocessor,
            @NonNull File temporaryDirectory,
            @NonNull Map<String, String> moduleSourceSet,
            @NonNull String packageName) {
        return createWriterWithoutPngCruncher(
                null,
                rootFolder,
                publicFile,
                blameLogFolder,
                preprocessor,
                temporaryDirectory,
                moduleSourceSet);
    }

    /** Used in tests */
    public static MergedResourceWriter createWriterWithoutPngCruncher(
            @Nullable ExecutorServiceAdapter executorServiceAdapter,
            @NonNull File rootFolder,
            @Nullable File publicFile,
            @Nullable File blameLogFolder,
            @NonNull ResourcePreprocessor preprocessor,
            @NonNull File temporaryDirectory,
            @NonNull Map<String, String> moduleSourceSet) {
        return new MergedResourceWriter(
                new MergedResourceWriterRequest(
                        // no need for multi-threading in tests.
                        new ExecutorServiceAdapter(MoreExecutors.newDirectExecutorService()),
                        rootFolder,
                        publicFile,
                        blameLogFolder != null
                                ? new MergingLog(blameLogFolder, moduleSourceSet)
                                : null,
                        preprocessor,
                        CopyToOutputDirectoryResourceCompilationService.INSTANCE,
                        temporaryDirectory,
                        null,
                        null,
                        false,
                        false,
                        moduleSourceSet));
    }

    @Override
    public void start(@NonNull DocumentBuilderFactory factory) throws ConsumerException {
        super.start(factory);
        mValuesResMap = ArrayListMultimap.create();
        mQualifierWithDeletedValues = Sets.newHashSet();
        mFactory = factory;
    }

    @Override
    public void end() throws ConsumerException {
        // Make sure all PNGs are generated first.
        super.end();
        // now perform all the databinding, PNG crunching (AAPT1) and resources compilation (AAPT2).
        try {
            File tmpDir = new File(mergeWriterRequest.getTemporaryDirectory(), "stripped.dir");
            try {
                FileUtils.cleanOutputDir(tmpDir);
            } catch (IOException e) {
                throw new ConsumerException(e);
            }

            while (!mCompileResourceRequests.isEmpty()) {
                CompileResourceRequest request = mCompileResourceRequests.poll();
                try {
                    File fileToCompile = request.getInputFile();

                    if (mMergingLog != null) {
                        File destination =
                                mergeWriterRequest
                                        .getResourceCompilationService()
                                        .compileOutputFor(request);
                        mMergingLog.logCopy(
                                fileToCompile,
                                getSourceFilePath(fileToCompile),
                                destination,
                                getSourceFilePath(destination));
                    }

                    if (mergeWriterRequest.getDataBindingExpressionRemover() != null
                            && request.getInputDirectoryName().startsWith("layout")
                            && request.getInputFile().getName().endsWith(".xml")) {

                        // Try to strip the layout. If stripping modified the file (there was data
                        // binding in the layout), compile the stripped layout into merged resources
                        // folder. Otherwise, compile into merged resources folder normally.

                        File strippedLayoutFolder =
                                new File(tmpDir, request.getInputDirectoryName());
                        File strippedLayout =
                                new File(strippedLayoutFolder, request.getInputFile().getName());

                        boolean removedDataBinding =
                                mergeWriterRequest
                                        .getDataBindingExpressionRemover()
                                        .processSingleFile(
                                                request.getInputFile(),
                                                strippedLayout,
                                                request.getInputFileIsFromDependency());

                        if (removedDataBinding) {
                            // Remember in case AAPT compile or link fails.
                            if (mMergingLog != null) {
                                mMergingLog.logCopy(
                                        request.getInputFile(),
                                        getSourceFilePath(request.getInputFile()),
                                        strippedLayout,
                                        getSourcePath(strippedLayout));
                            }
                            fileToCompile = strippedLayout;
                        } else {
                            mergeWriterRequest
                                    .getDataBindingExpressionRemover()
                                    .processFileWithNoDataBinding(request.getInputFile());
                        }
                    }

                    // Currently the resource shrinker and unit tests that use resources need
                    // the final merged, but uncompiled file.
                    if (mergeWriterRequest.getNotCompiledOutputDirectory() != null) {
                        File typeDir =
                                new File(
                                        mergeWriterRequest.getNotCompiledOutputDirectory(),
                                        request.getInputDirectoryName());
                        FileUtils.mkdirs(typeDir);
                        FileUtils.copyFileToDirectory(fileToCompile, typeDir);
                    }

                    CompileResourceRequest compileResourceRequest =
                            new CompileResourceRequest(
                                    fileToCompile,
                                    request.getOutputDirectory(),
                                    request.getInputDirectoryName(),
                                    request.getInputFileIsFromDependency(),
                                    mergeWriterRequest.getPseudoLocalesEnabled(),
                                    mergeWriterRequest.getCrunchPng(),
                                    ImmutableMap.of(),
                                    request.getInputFile());
                    if (!mergeWriterRequest.getModuleSourceSets().isEmpty()) {
                        compileResourceRequest.useRelativeSourcePath(
                                mergeWriterRequest.getModuleSourceSets());
                    }

                    mergeWriterRequest
                            .getResourceCompilationService()
                            .submitCompile(compileResourceRequest);

                    mCompiledFileMap.put(
                            compileResourceRequest.getSourcePath(),
                            mergeWriterRequest
                                    .getResourceCompilationService()
                                    .compileOutputFor(request)
                                    .getPath());
                } catch (Exception e) {
                    throw MergingException.wrapException(e)
                            .withFile(request.getInputFile())
                            .build();
                }
            }
        } catch (Exception e) {
            throw new ConsumerException(e);
        }

        if (mMergingLog != null) {
            try {
                mMergingLog.write();
            } catch (IOException e) {
                throw new ConsumerException(e);
            }
            mMergingLog = null;
        }

        mValuesResMap = null;
        mQualifierWithDeletedValues = null;
        mFactory = null;

        try (FileWriter fw = new FileWriter(mCompiledFileMapFile)) {
            mCompiledFileMap.store(fw, null);
        } catch (IOException e) {
            throw new ConsumerException(e);
        }
    }

    private String getSourceFilePath(File inputFile) {
        return mergeWriterRequest.getModuleSourceSets().isEmpty()
                ? inputFile.getAbsolutePath()
                : RelativeResourceUtils.getRelativeSourceSetPath(
                        inputFile, mergeWriterRequest.getModuleSourceSets());
    }

    @Override
    public boolean ignoreItemInMerge(ResourceMergerItem item) {
        return item.getIgnoredFromDiskMerge();
    }

    @Override
    public void addItem(@NonNull final ResourceMergerItem item) throws ConsumerException {
        final ResourceFile.FileType type = item.getSourceType();

        if (type == ResourceFile.FileType.XML_VALUES) {
            // this is a resource for the values files

            // just add the node to write to the map based on the qualifier.
            // We'll figure out later if the files needs to be written or (not)
            mValuesResMap.put(item.getQualifiers(), item);
        } else {
            checkState(item.getSourceFile() != null);
            // This is a single value file or a set of generated files. Only write it if the state
            // is TOUCHED.
            if (item.isTouched()) {
                File file = item.getFile();
                String folderName = getFolderName(item);

                // TODO : make this also a request and use multi-threading for generation.
                if (type == DataFile.FileType.GENERATED_FILES) {
                    try {
                        FileGenerationParameters workItem =
                                new FileGenerationParameters(
                                        item, mergeWriterRequest.getPreprocessor());
                        if (workItem.resourceItem.getSourceFile() != null) {
                            getExecutor().submit(new FileGenerationWorkAction(workItem));
                        }
                    } catch (Exception e) {
                        throw new ConsumerException(e, item.getSourceFile().getFile());
                    }
                }

                // enlist a new crunching request.
                CompileResourceRequest crunchRequest =
                        new CompileResourceRequest(
                                file, getRootFolder(), folderName, item.mIsFromDependency);
                if (!mergeWriterRequest.getModuleSourceSets().isEmpty()) {
                    crunchRequest.useRelativeSourcePath(mergeWriterRequest.getModuleSourceSets());
                }
                mCompileResourceRequests.add(crunchRequest);
            }
        }
    }

    public static class FileGenerationParameters implements Serializable {
        public final ResourceMergerItem resourceItem;
        public final ResourcePreprocessor resourcePreprocessor;

        private FileGenerationParameters(
                ResourceMergerItem resourceItem, ResourcePreprocessor resourcePreprocessor) {
            this.resourceItem = resourceItem;
            this.resourcePreprocessor = resourcePreprocessor;
        }
    }

    public static class FileGenerationWorkAction implements WorkerExecutorFacade.WorkAction {

        private final FileGenerationParameters workItem;

        @Inject
        public FileGenerationWorkAction(FileGenerationParameters workItem) {
            this.workItem = workItem;
        }

        @Override
        public void run() {
            try {
                workItem.resourcePreprocessor.generateFile(
                        workItem.resourceItem.getFile(),
                        workItem.resourceItem.getSourceFile().getFile());
            } catch (Exception e) {
                throw new RuntimeException(
                        "Error while processing "
                                + workItem.resourceItem.getSourceFile().getFile()
                                + " : "
                                + e.getMessage(),
                        e);
            }
        }
    }

    @Override
    public void removeItem(
            @NonNull ResourceMergerItem removedItem, @Nullable ResourceMergerItem replacedBy) {
        ResourceFile.FileType removedType = removedItem.getSourceType();
        ResourceFile.FileType replacedType = replacedBy != null
                ? replacedBy.getSourceType()
                : null;

        switch (removedType) {
            case SINGLE_FILE: // Fall through.
            case GENERATED_FILES:
                if (replacedType == DataFile.FileType.SINGLE_FILE
                        || replacedType == DataFile.FileType.GENERATED_FILES) {
                    File removedFile = getResourceOutputFile(removedItem);
                    File replacedFile = getResourceOutputFile(replacedBy);
                    if (removedFile.equals(replacedFile)) {
                        /*
                         * There are two reasons to skip this: 1. we save an IO operation by
                         * deleting a file that will be overwritten. 2. if we did delete the file,
                         * we would have to be careful about concurrency to make sure we would be
                         * deleting the *old* file and not the overwritten version.
                         */
                        break;
                    }
                }
                removeOutFile(removedItem);
                break;
            case XML_VALUES:
                mQualifierWithDeletedValues.add(removedItem.getQualifiers());
                break;
            default:
                throw new IllegalStateException();
        }
    }

    @Override
    protected void postWriteAction() throws ConsumerException {

        /*
         * Create a temporary directory where merged XML files are placed before being processed
         * by the resource compiler.
         */
        File tmpDir =
                new File(
                        mergeWriterRequest.getTemporaryDirectory(), SdkConstants.FD_MERGED_DOT_DIR);
        try {
            FileUtils.cleanOutputDir(tmpDir);
        } catch (IOException e) {
            throw new ConsumerException(e);
        }

        // now write the values files.
        for (String key : mValuesResMap.keySet()) {
            // the key is the qualifier.

            // check if we have to write the file due to deleted values.
            // also remove it from that list anyway (to detect empty qualifiers later).
            boolean mustWriteFile = mQualifierWithDeletedValues.remove(key);

            // get the list of items to write
            List<ResourceMergerItem> items = mValuesResMap.get(key);

            // now check if we really have to write it
            if (!mustWriteFile) {
                for (ResourceMergerItem item : items) {
                    if (item.isTouched()) {
                        mustWriteFile = true;
                        break;
                    }
                }
            }

            if (mustWriteFile) {
                /*
                 * We will write the file to a temporary directory. If the folder name is "values",
                 * we will write the XML file to "<tmpdir>/values/values.xml". If the folder name
                 * is "values-XXX" we will write the XML file to
                 * "<tmpdir/values-XXX/values-XXX.xml".
                 *
                 * Then, we will issue a compile operation or copy the file if aapt does not require
                 * compilation of this file.
                 */
                try {
                    String folderName = key.isEmpty() ?
                            ResourceFolderType.VALUES.getName() :
                            ResourceFolderType.VALUES.getName() + RES_QUALIFIER_SEP + key;

                    File valuesFolder = new File(tmpDir, folderName);
                    // Name of the file is the same as the folder as AAPT gets confused with name
                    // collision when not normalizing folders name.
                    File outFile = new File(valuesFolder, folderName + DOT_XML);

                    FileUtils.mkdirs(valuesFolder);

                    DocumentBuilder builder = mFactory.newDocumentBuilder();
                    Document document = builder.newDocument();
                    final String publicTag = ResourceType.PUBLIC.getName();
                    List<Node> publicNodes = null;

                    Node rootNode = document.createElement(TAG_RESOURCES);
                    document.appendChild(rootNode);

                    Collections.sort(items);

                    for (ResourceMergerItem item : items) {
                        Node nodeValue = item.getValue();
                        if (nodeValue != null && publicTag.equals(nodeValue.getNodeName())) {
                            if (publicNodes == null) {
                                publicNodes = Lists.newArrayList();
                            }
                            publicNodes.add(nodeValue);
                            continue;
                        }

                        // add a carriage return so that the nodes are not all on the same line.
                        // also add an indent of 4 spaces.
                        rootNode.appendChild(document.createTextNode("\n    "));

                        ResourceFile source = item.getSourceFile();

                        Node adoptedNode = NodeUtils.adoptNode(document, nodeValue);
                        if (source != null) {
                            if (adoptedNode.hasChildNodes()) {
                                // Nested resources e.g. style and st types can contain comments,
                                // so these need to be stripped.
                                for (int i = 0; i < adoptedNode.getChildNodes().getLength(); i++) {
                                    Node child = adoptedNode.getChildNodes().item(i);
                                    if (child instanceof Comment) {
                                        adoptedNode.removeChild(child);
                                    }
                                }
                                // Removes empty lines and spaces from nested resource tags.
                                adoptedNode.normalize();
                            }
                            XmlUtils.attachSourceFile(
                                    adoptedNode, new SourceFile(source.getFile()));
                        }
                        rootNode.appendChild(adoptedNode);
                    }

                    // finish with a carriage return
                    rootNode.appendChild(document.createTextNode("\n"));

                    final String content;
                    Map<SourcePosition, SourceFilePosition> blame =
                            mMergingLog == null ? null : Maps.newLinkedHashMap();

                    if (blame != null) {
                        content = XmlUtils.toXml(document, blame);
                    } else {
                        content = XmlUtils.toXml(document);
                    }

                    Files.asCharSink(outFile, Charsets.UTF_8).write(content);

                    CompileResourceRequest request =
                            new CompileResourceRequest(
                                    outFile,
                                    getRootFolder(),
                                    folderName,
                                    null,
                                    mergeWriterRequest.getPseudoLocalesEnabled(),
                                    mergeWriterRequest.getCrunchPng(),
                                    blame != null ? blame : ImmutableMap.of(),
                                    outFile);
                    if (!mergeWriterRequest.getModuleSourceSets().isEmpty()) {
                        request.useRelativeSourcePath(mergeWriterRequest.getModuleSourceSets());
                    }

                    // If we are going to shrink resources, the resource shrinker needs to have the
                    // final merged uncompiled file.
                    if (mergeWriterRequest.getNotCompiledOutputDirectory() != null) {
                        File typeDir =
                                new File(
                                        mergeWriterRequest.getNotCompiledOutputDirectory(),
                                        folderName);
                        FileUtils.mkdirs(typeDir);
                        FileUtils.copyFileToDirectory(outFile, typeDir);
                    }

                    if (blame != null) {
                        File file =
                                mergeWriterRequest
                                        .getResourceCompilationService()
                                        .compileOutputFor(request);
                        String fileSourcePath = getSourceFilePath(file);
                        mMergingLog.logSource(new SourceFile(file), fileSourcePath, blame);

                        String outFileSourcePath = getSourceFilePath(outFile);
                        mMergingLog.logSource(new SourceFile(outFile), outFileSourcePath, blame);
                    }

                    mergeWriterRequest.getResourceCompilationService().submitCompile(request);

                    if (publicNodes != null && mergeWriterRequest.getPublicFile() != null) {
                        // Generate public.txt:
                        int size = publicNodes.size();
                        StringBuilder sb = new StringBuilder(size * 80);
                        for (Node node : publicNodes) {
                            if (node.getNodeType() == Node.ELEMENT_NODE) {
                                Element element = (Element) node;
                                String name = element.getAttribute(ATTR_NAME);
                                String type = element.getAttribute(ATTR_TYPE);
                                if (!name.isEmpty() && !type.isEmpty()) {
                                    String flattenedName = name.replace('.', '_');
                                    sb.append(type).append(' ').append(flattenedName).append('\n');
                                }
                            }
                        }
                        File parentFile = mergeWriterRequest.getPublicFile().getParentFile();
                        if (!parentFile.exists()) {
                            boolean mkdirs = parentFile.mkdirs();
                            if (!mkdirs) {
                                throw new IOException("Could not create " + parentFile);
                            }
                        }
                        String text = sb.toString();
                        Files.asCharSink(mergeWriterRequest.getPublicFile(), Charsets.UTF_8)
                                .write(text);
                    }
                } catch (Exception e) {
                    throw new ConsumerException(e);
                }
            }
        }

        // now remove empty values files.
        for (String key : mQualifierWithDeletedValues) {
            String folderName = key != null && !key.isEmpty() ?
                    ResourceFolderType.VALUES.getName() + RES_QUALIFIER_SEP + key :
                    ResourceFolderType.VALUES.getName();

            if (mergeWriterRequest.getNotCompiledOutputDirectory() != null) {
                removeOutFile(
                        FileUtils.join(
                                mergeWriterRequest.getNotCompiledOutputDirectory(),
                                folderName,
                                folderName + DOT_XML));
            }

            // Remove the intermediate (compiled) values file.
            CompileResourceRequest compileResourceRequest =
                    new CompileResourceRequest(
                            FileUtils.join(getRootFolder(), folderName, folderName + DOT_XML),
                            getRootFolder(),
                            folderName);
            if (!mergeWriterRequest.getModuleSourceSets().isEmpty()) {
                compileResourceRequest.useRelativeSourcePath(
                        mergeWriterRequest.getModuleSourceSets());
            }
            removeOutFile(
                    mergeWriterRequest
                            .getResourceCompilationService()
                            .compileOutputFor(compileResourceRequest));
        }
    }

    private String getSourcePath(File file) {
        return mergeWriterRequest.getModuleSourceSets().isEmpty()
                ? file.getAbsolutePath()
                : RelativeResourceUtils.getRelativeSourceSetPath(
                        file, mergeWriterRequest.getModuleSourceSets());
    }

    /**
     * Obtains the where te merged resource is located.
     *
     * @param resourceItem the resource item
     * @return the file
     */
    @NonNull
    private File getResourceOutputFile(@NonNull ResourceMergerItem resourceItem) {
        File file = resourceItem.getFile();
        String compiledFilePath = mCompiledFileMap.getProperty(file.getAbsolutePath());
        if (compiledFilePath != null) {
            return new File(compiledFilePath);
        } else {
            return mergeWriterRequest
                    .getResourceCompilationService()
                    .compileOutputFor(
                            new CompileResourceRequest(
                                    file,
                                    getRootFolder(),
                                    getFolderName(resourceItem),
                                    resourceItem.mIsFromDependency));
        }
    }

    /**
     * Removes possibly existing layout file from the data binding output folder. If the original
     * file was a layout XML file it is possible that it contained data binding and was put into the
     * data binding layout output folder for data binding tasks to process.
     *
     * @param resourceItem the source item that could have created the file to remove
     */
    private void removeLayoutFileFromDataBindingOutputFolder(
            @NonNull ResourceMergerItem resourceItem) {
        File originalFile = resourceItem.getFile();
        // Only files that come from layout folders and are XML files could have been stripped.
        if (!originalFile.getParentFile().getName().startsWith("layout")
                || !originalFile.getName().endsWith(".xml")) {
            return;
        }
        mergeWriterRequest.getDataBindingExpressionRemover().processRemovedFile(originalFile);
    }

    private void removeFileFromNotCompiledOutputDir(@NonNull ResourceMergerItem resourceItem) {
        File originalFile = resourceItem.getFile();
        File resTypeDir =
                new File(
                        mergeWriterRequest.getNotCompiledOutputDirectory(),
                        originalFile.getParentFile().getName());
        File toRemove = new File(resTypeDir, originalFile.getName());
        removeOutFile(toRemove);
    }

    /**
     * Removes a file that already exists in the out res folder. This has to be a non value file.
     *
     * @param resourceItem the source item that created the file to remove, this item must have a
     *     file associated with it
     * @return true if success.
     */
    private boolean removeOutFile(ResourceMergerItem resourceItem) {
        File fileToRemove = getResourceOutputFile(resourceItem);
        if (mergeWriterRequest.getDataBindingExpressionRemover() != null) {
            // The file could have possibly been a layout file with data binding.
            removeLayoutFileFromDataBindingOutputFolder(resourceItem);
        }
        if (mergeWriterRequest.getNotCompiledOutputDirectory() != null) {
            // The file was copied for the resource shrinking and needs to be removed from there.
            removeFileFromNotCompiledOutputDir(resourceItem);
        }
        return removeOutFile(fileToRemove);
    }

    /**
     * Removes a file from a folder based on a sub folder name and a filename
     *
     * @param fileToRemove the file to remove
     * @return true if success
     */
    private boolean removeOutFile(@NonNull File fileToRemove) {
        if (mMergingLog != null) {
            SourceFile removeSourceFile = new SourceFile(fileToRemove);
            String sourcePath = getSourceFilePath(fileToRemove);
            removeSourceFile.setOverrideSourcePath(sourcePath);
            mMergingLog.logRemove(removeSourceFile);
        }

        return fileToRemove.delete();
    }

    /**
     * Calculates the right folder name give a resource item.
     *
     * @param resourceItem the resource item to calculate the folder name from.
     * @return a relative folder name
     */
    @NonNull
    private static String getFolderName(ResourceMergerItem resourceItem) {
        ResourceType itemType = resourceItem.getType();
        String folderName = itemType.getName();
        String qualifiers = resourceItem.getQualifiers();
        if (!qualifiers.isEmpty()) {
            folderName = folderName + RES_QUALIFIER_SEP + qualifiers;
        }
        return folderName;
    }
}
