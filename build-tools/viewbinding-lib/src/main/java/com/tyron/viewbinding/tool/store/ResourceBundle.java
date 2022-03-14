package com.tyron.viewbinding.tool.store;

import com.tyron.viewbinding.tool.processing.ErrorMessages;
import com.tyron.viewbinding.tool.processing.Scope;
import com.tyron.viewbinding.tool.processing.ScopedException;
import com.tyron.viewbinding.tool.processing.scopes.FileScopeProvider;
import com.tyron.viewbinding.tool.processing.scopes.LocationScopeProvider;
import com.tyron.viewbinding.tool.util.L;
import com.tyron.viewbinding.tool.util.ParserHelper;
import com.tyron.viewbinding.tool.util.Preconditions;
import com.tyron.viewbinding.tool.util.RelativizableFile;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This is a serializable class that can keep the result of parsing layout files.
 */
public class ResourceBundle implements Serializable {
    /**
     * Produce a fully-qualified type name for an XML layout node using the same rules as the
     * platform's {@code LayoutInflater}.
     */
    private static String qualifyViewNodeName(String viewNodeName) {
        switch (viewNodeName) {
            case "View":
            case "ViewGroup":
            case "ViewStub":
            case "TextureView":
            case "SurfaceView":
                return "android.view." + viewNodeName;
            case "WebView":
                return "android.webkit.WebView";
            default:
                return viewNodeName.indexOf('.') == -1
                        ? "android.widget." + viewNodeName
                        : viewNodeName;
        }
    }

    private String mAppPackage;

    private HashMap<String, List<LayoutFileBundle>> mLayoutBundles
            = new HashMap<String, List<LayoutFileBundle>>();

    private Set<LayoutFileBundle> mLayoutFileBundlesInSource = new HashSet<>();

    private Map<String, IncludedLayout> mDependencyBinders = new HashMap<>();

    /**
     * Layout files that were removed. We track these files to delete stale layout info files that
     * data binding generated in the previous build.
     */
    @NonNull private List<File> mRemovedFiles = new ArrayList<File>();

    /**
     * Layout files that exist in the current build but do not contain data binding constructs.
     * These include:
     *   1. Layout files that previously contained data binding constructs but are now no longer
     *      containing them. We track these files to delete stale layout info files that data
     *      binding generated in the previous build (see bug 153711619).
     *   2. Layout files that do not have a history or did not have data binding constructs in the
     *      previous build. We don't really need to track these files as there are no corresponding
     *      stale layout info files to delete, but it's easier (and okay) to leave them here.
     */
    @NonNull private List<File> mFileWithNoDataBinding = new ArrayList<>();

    private final String viewDataBindingClass;

    public ResourceBundle(String appPackage, boolean useAndroidX) {
        mAppPackage = appPackage;
        viewDataBindingClass = useAndroidX
                ? "androidx.databinding.ViewDataBinding"
                : "android.databinding.ViewDataBinding";
    }

    public void addLayoutBundle(@NonNull LayoutFileBundle bundle, boolean fromSource) {
        if (bundle.mFileName == null) {
            L.e("File bundle must have a name. %s does not have one.", bundle);
            return;
        }
        // we want to generate only if this belongs to us, otherwise, it is already generated in
        // the dependency
        if (fromSource) {
            mLayoutFileBundlesInSource.add(bundle);
        }

        List<LayoutFileBundle> bundles = mLayoutBundles
                .computeIfAbsent(bundle.mFileName, ignored -> new ArrayList<>());
        for (LayoutFileBundle existing : bundles) {
            if (existing.equals(bundle)) {
                L.d("skipping layout bundle %s because it already exists.", bundle);
                return;
            }
        }
        L.d("adding bundle %s", bundle);
        bundles.add(bundle);
    }

    /*public void addDependencyLayouts(GenClassInfoLog genClassInfoLog) {
        genClassInfoLog.mappings().forEach(
                (key, value) -> mDependencyBinders.put(key,
                    new IncludedLayout.Builder()
                        .layoutName(key)
                        .modulePackage(value.getModulePackage())
                        .interfaceQName(value.getQName())
                    .build()));
    }*/

    /**
     * @deprecated Use {@link #getAllLayoutFileBundlesInSource()} which contains
     * {@link LayoutFileBundle}s for all layouts instead of just data binding. If you only care
     * about data binding layouts, check {@link LayoutFileBundle#isBindingData()}.
     */
    @Deprecated
    public Set<LayoutFileBundle> getLayoutFileBundlesInSource() {
        return Sets.filter(mLayoutFileBundlesInSource, LayoutFileBundle::isBindingData);
    }

    public Set<LayoutFileBundle> getAllLayoutFileBundlesInSource() {
        return mLayoutFileBundlesInSource;
    }

    /**
     * @deprecated Use {@link #getAllLayoutBundles()} which contains {@link LayoutFileBundle}s for
     * all layouts instead of just data binding. If you only care about data binding layouts, check
     * {@link LayoutFileBundle#isBindingData()}.
     */
    @Deprecated
    public Map<String, List<LayoutFileBundle>> getLayoutBundles() {
        //noinspection ConstantConditions Map values are never null.
        return Maps.filterEntries(mLayoutBundles, entry -> entry.getValue().get(0).isBindingData());
    }

    public Map<String, List<LayoutFileBundle>> getAllLayoutBundles() {
        return mLayoutBundles;
    }

    public String getAppPackage() {
        return mAppPackage;
    }

    /**
     * Loads the class info from a folder. This log has the list of classes which are generated
     * in previous steps.
     */

    /*
    public static GenClassInfoLog loadClassInfoFromFolder(File folder) throws IOException {
        GenClassInfoLog merged = new GenClassInfoLog();
        // blaze might pass a zip instead of a folder
        if (folder.isFile()) { //bazel
            // unzip it into a tmp folder and use it.
            try (ZipFile zipFile = new ZipFile(folder)) {
                zipFile.stream().forEach(zipEntry -> {
                    if (zipEntry.getName().endsWith(DataBindingBuilder.BINDING_CLASS_LIST_SUFFIX)) {
                        try {
                            merged.addAll(GenClassInfoLog.fromInputStream(zipFile.getInputStream
                                    (zipEntry)));
                        } catch (IOException e) {
                            L.e(
                                    e,
                                    "failed to read gen class info log from entry %s",
                                    zipEntry.getName());
                        }
                    }
                });
            }
        } else if (folder.isDirectory()){
            SuffixFileFilter fileFilter = new SuffixFileFilter(
                DataBindingBuilder.BINDING_CLASS_LIST_SUFFIX,
                IOCase.SYSTEM);
            Collection<File> files = FileUtils.listFiles(folder,
                fileFilter,
                TrueFileFilter.INSTANCE);
            for (File file : files) {
                merged.addAll(GenClassInfoLog.fromFile(file));
            }
        } else {
            // happens w/ blaze
            L.w("no info log is passed. There are no resources?");
        }
        return merged;
    }*/

    public void validateAndRegisterErrors() {
        validateBindingTargetIds();
        validateMultiResLayouts();
    }

    private void validateBindingTargetIds() {
        mLayoutBundles.forEach((name, layoutFileBundles) -> {
            layoutFileBundles.forEach(bundle -> {
                Set<String> ids = new HashSet<>();
                Set<String> conflictingIds = new HashSet<>();
                bundle.getBindingTargetBundles().forEach(bindingTarget -> {
                    String id = bindingTarget.getId();
                    if (id != null && !ids.add(id)) {
                        conflictingIds.add(id);
                    }
                });
                bundle.getBindingTargetBundles().forEach(bindingTarget -> {
                    String id = bindingTarget.getId();
                    if (id != null && conflictingIds.contains(id)) {
                        String tag;
                        if (bindingTarget.mViewName != null) {
                            tag = bindingTarget.mViewName;
                        } else if (bindingTarget.mIncludedLayout != null) {
                            tag = "include";
                        } else {
                            // Ideally we never hit this fallback case, but just in case, the tag
                            // probably represents a view of some sort.
                            tag = "view";
                        }

                        String error = String.format(ErrorMessages.DUPLICATE_VIEW_OR_INCLUDE_ID, tag, id);
                        Scope.registerError(error, bundle, bindingTarget);
                    }
                });
            });
        });
    }


    private void validateMultiResLayouts() {
        for (List<LayoutFileBundle> layoutFileBundles : mLayoutBundles.values()) {
            for (LayoutFileBundle layoutFileBundle : layoutFileBundles) {
                List<BindingTargetBundle> unboundIncludes = new ArrayList<>();
                for (BindingTargetBundle target : layoutFileBundle.getBindingTargetBundles()) {
                    if (target.isBinder()) {
                        List<LayoutFileBundle> boundTo =
                                mLayoutBundles.get(target.getIncludedLayout());
                        String targetBinding = null;
                        String targetBindingPackage = null;
                        if (boundTo != null && !boundTo.isEmpty()) {
                            targetBinding = boundTo.get(0).getFullBindingClass();
                            targetBindingPackage = boundTo.get(0).getModulePackage();
                        } else {
                            IncludedLayout included = mDependencyBinders.getOrDefault(
                                    target.getIncludedLayout(), null);
                            if (included != null) {
                                targetBinding = included.interfaceQName;
                                targetBindingPackage = included.modulePackage;
                            }
                        }
                        if (targetBinding == null) {
                            L.d("There is no binding for %s, reverting to plain layout",
                                    target.getIncludedLayout());
                            if (target.getId() == null) {
                                unboundIncludes.add(target);
                            } else {
                                target.setInterfaceType("android.view.View");
                                target.mViewName = "android.view.View";
                            }
                        } else {
                            target.setInterfaceType(targetBinding, targetBindingPackage);
                        }
                    }
                }
                layoutFileBundle.getBindingTargetBundles().removeAll(unboundIncludes);
            }
        }

        for (Map.Entry<String, List<LayoutFileBundle>> bundles : mLayoutBundles.entrySet()) {
            if (bundles.getValue().size() < 2) {
                continue;
            }

            // validate all ids are in correct view types
            // and all variables have the same name
            for (LayoutFileBundle bundle : bundles.getValue()) {
                bundle.mHasVariations = true;
            }
            String bindingClass = validateAndGetSharedClassName(bundles.getValue());
            Map<String, NameTypeLocation> variableTypes = validateAndMergeNameTypeLocations(
                    bundles.getValue(), ErrorMessages.MULTI_CONFIG_VARIABLE_TYPE_MISMATCH,
                    new ValidateAndFilterCallback() {
                        @Override
                        public List<? extends NameTypeLocation> get(LayoutFileBundle bundle) {
                            return bundle.mVariables;
                        }
                    });

            Map<String, NameTypeLocation> importTypes = validateAndMergeNameTypeLocations(
                    bundles.getValue(), ErrorMessages.MULTI_CONFIG_IMPORT_TYPE_MISMATCH,
                    new ValidateAndFilterCallback() {
                        @Override
                        public List<NameTypeLocation> get(LayoutFileBundle bundle) {
                            return bundle.mImports;
                        }
                    });

            for (LayoutFileBundle bundle : bundles.getValue()) {
                // now add missing ones to each to ensure they can be referenced
                L.d("checking for missing variables in %s / %s", bundle.mFileName,
                        bundle.mConfigName);
                for (Map.Entry<String, NameTypeLocation> variable : variableTypes.entrySet()) {
                    if (!NameTypeLocation.contains(bundle.mVariables, variable.getKey())) {
                        NameTypeLocation orig = variable.getValue();
                        bundle.addVariable(orig.name, orig.type, orig.location, false);
                        L.d("adding missing variable %s to %s / %s", variable.getKey(),
                                bundle.mFileName, bundle.mConfigName);
                    }
                }
                for (Map.Entry<String, NameTypeLocation> userImport : importTypes.entrySet()) {
                    if (!NameTypeLocation.contains(bundle.mImports, userImport.getKey())) {
                        bundle.mImports.add(userImport.getValue());
                        L.d("adding missing import %s to %s / %s", userImport.getKey(),
                                bundle.mFileName, bundle.mConfigName);
                    }
                }
            }

            Set<String> includeBindingIds = new HashSet<String>();
            Set<String> viewBindingIds = new HashSet<String>();
            Map<String, String> viewTypes = new HashMap<String, String>();
            Map<String, String> includes = new HashMap<String, String>();
            L.d("validating ids for %s", bundles.getKey());
            Set<String> conflictingIds = new HashSet<String>();
            for (LayoutFileBundle bundle : bundles.getValue()) {
                try {
                    Scope.enter(bundle);
                    for (BindingTargetBundle target : bundle.mBindingTargetBundles) {
                        try {
                            Scope.enter(target);
                            L.d("checking %s %s %s", target.getId(), target.getFullClassName(),
                                    target.isBinder());
                            if (target.mId != null) {
                                if (target.isBinder()) {
                                    if (viewBindingIds.contains(target.mId)) {
                                        L.d("%s is conflicting", target.mId);
                                        conflictingIds.add(target.mId);
                                        continue;
                                    }
                                    includeBindingIds.add(target.mId);
                                } else {
                                    if (includeBindingIds.contains(target.mId)) {
                                        L.d("%s is conflicting", target.mId);
                                        conflictingIds.add(target.mId);
                                        continue;
                                    }
                                    viewBindingIds.add(target.mId);
                                }
                                String existingType = viewTypes.get(target.mId);
                                if (existingType == null) {
                                    L.d("assigning %s as %s", target.getId(),
                                            target.getFullClassName());
                                    viewTypes.put(target.mId, target.getFullClassName());
                                    if (target.isBinder()) {
                                        includes.put(target.mId, target.getIncludedLayout());
                                    }
                                } else if (!existingType.equals(target.getFullClassName())) {
                                    if (target.isBinder()) {
                                        L.d("overriding %s as base binder", target.getId());
                                        viewTypes.put(target.mId, viewDataBindingClass);
                                        includes.put(target.mId, target.getIncludedLayout());
                                    } else {
                                        L.d("overriding %s as base view", target.getId());
                                        viewTypes.put(target.mId, "android.view.View");
                                    }
                                }
                            }
                        } catch (ScopedException ex) {
                            Scope.defer(ex);
                        } finally {
                            Scope.exit();
                        }
                    }
                } finally {
                    Scope.exit();
                }
            }

            if (!conflictingIds.isEmpty()) {
                for (LayoutFileBundle bundle : bundles.getValue()) {
                    for (BindingTargetBundle target : bundle.mBindingTargetBundles) {
                        if (conflictingIds.contains(target.mId)) {
                            Scope.registerError(String.format(
                                    ErrorMessages.MULTI_CONFIG_ID_USED_AS_IMPORT,
                                    target.mId), bundle, target);
                        }
                    }
                }
            }

            for (LayoutFileBundle bundle : bundles.getValue()) {
                try {
                    Scope.enter(bundle);
                    for (Map.Entry<String, String> viewType : viewTypes.entrySet()) {
                        BindingTargetBundle target = bundle.getBindingTargetById(viewType.getKey());
                        if (target == null) {
                            String include = includes.get(viewType.getKey());
                            if (include == null) {
                                bundle.createBindingTarget(viewType.getKey(), viewType.getValue(),
                                        false, null, null, null);
                            } else {
                                BindingTargetBundle bindingTargetBundle = bundle
                                        .createBindingTarget(
                                                viewType.getKey(), null, false, null, null, null);
                                bindingTargetBundle.setIncludedLayout(include);
                                bindingTargetBundle.setInterfaceType(viewType.getValue());
                            }
                        } else {
                            L.d("setting interface type on %s (%s) as %s", target.mId,
                                    target.getFullClassName(), viewType.getValue());
                            target.setInterfaceType(viewType.getValue());
                        }
                    }
                } catch (ScopedException ex) {
                    Scope.defer(ex);
                } finally {
                    Scope.exit();
                }
            }
        }
        // assign class names to each
        for (Map.Entry<String, List<LayoutFileBundle>> entry : mLayoutBundles.entrySet()) {
            for (LayoutFileBundle bundle : entry.getValue()) {
                final String configName;
                if (bundle.hasVariations()) {
                    // append configuration specifiers.
                    final String parentFileName = bundle.mDirectory;
                    L.d("parent file for %s is %s", bundle.getFileName(), parentFileName);
                    if ("layout".equals(parentFileName)) {
                        configName = "";
                    } else {
                        configName = ParserHelper.toClassName(
                                parentFileName.substring("layout-".length()));
                    }
                } else {
                    configName = "";
                }
                bundle.mConfigName = configName;
            }
        }
    }

    /**
     * Receives a list of bundles which are representations of the same layout file in different
     * configurations.
     *
     * @return The map for variables and their types
     */
    private Map<String, NameTypeLocation> validateAndMergeNameTypeLocations(
            List<LayoutFileBundle> bundles, String errorMessage,
            ValidateAndFilterCallback callback) {
        Map<String, NameTypeLocation> result = new HashMap<String, NameTypeLocation>();
        Set<String> mismatched = new HashSet<String>();
        for (LayoutFileBundle bundle : bundles) {
            for (NameTypeLocation item : callback.get(bundle)) {
                NameTypeLocation existing = result.get(item.name);
                if (existing != null && !existing.type.equals(item.type)) {
                    mismatched.add(item.name);
                    continue;
                }
                result.put(item.name, item);
            }
        }
        if (mismatched.isEmpty()) {
            return result;
        }
        // create exceptions. We could get more clever and find the outlier but for now, listing
        // each file w/ locations seems enough
        for (String mismatch : mismatched) {
            for (LayoutFileBundle bundle : bundles) {
                NameTypeLocation found = null;
                for (NameTypeLocation item : callback.get(bundle)) {
                    if (mismatch.equals(item.name)) {
                        found = item;
                        break;
                    }
                }
                if (found == null) {
                    // variable is not defined in this layout, continue
                    continue;
                }
                Scope.registerError(String.format(
                        errorMessage, found.name, found.type,
                        bundle.mDirectory + "/" + bundle.getFileName()), bundle,
                        found.location.createScope());
            }
        }
        return result;
    }

    /**
     * Receives a list of bundles which are representations of the same layout file in different
     * configurations.
     *
     * @return The shared class name for these bundles
     */
    private String validateAndGetSharedClassName(List<LayoutFileBundle> bundles) {
        String sharedClassName = null;
        boolean hasMismatch = false;
        for (LayoutFileBundle bundle : bundles) {
            bundle.mHasVariations = true;
            String fullBindingClass = bundle.getFullBindingClass();
            if (sharedClassName == null) {
                sharedClassName = fullBindingClass;
            } else if (!sharedClassName.equals(fullBindingClass)) {
                hasMismatch = true;
                break;
            }
        }
        if (!hasMismatch) {
            return sharedClassName;
        }
        // generate proper exceptions for each
        for (LayoutFileBundle bundle : bundles) {
            Scope.registerError(String.format(ErrorMessages.MULTI_CONFIG_LAYOUT_CLASS_NAME_MISMATCH,
                    bundle.getFullBindingClass(), bundle.mDirectory + "/" + bundle.getFileName()),
                    bundle, bundle.getClassNameLocationProvider());
        }
        return sharedClassName;
    }

    public void addRemovedFile(File file) {
        mRemovedFiles.add(file);
    }

    public List<File> getRemovedFiles() {
        return mRemovedFiles;
    }

    public void addFileWithNoDataBinding(@NonNull File file) {
        mFileWithNoDataBinding.add(file);
    }

    @NonNull
    public List<File> getFilesWithNoDataBinding() {
        return new ArrayList<>(mFileWithNoDataBinding);
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @XmlRootElement(name = "Layout")
    public static class LayoutFileBundle implements Serializable, FileScopeProvider {
        @XmlAttribute(name = "layout", required = true)
        public String mFileName;
        @XmlAttribute(name = "modulePackage", required = true)
        public String mModulePackage;

        /**
         * The path to the original layout file. It could be an absolute path or a relative path.
         */
        @XmlAttribute(name = "filePath", required = true)
        public String mFilePath;

        private String mConfigName;

        // The binding class as given by the user
        @XmlAttribute(name = "bindingClass", required = false)
        public String mBindingClass;

        // The location of the name of the generated class, optional
        @XmlElement(name = "ClassNameLocation", required = false)
        private Location mClassNameLocation;
        // The full package and class name as determined from mBindingClass and mModulePackage
        private String mFullBindingClass;

        // The simple binding class name as determined from mBindingClass and mModulePackage
        private String mBindingClassName;

        // The package of the binding class as determined from mBindingClass and mModulePackage
        private String mBindingPackage;

        @XmlAttribute(name = "directory", required = true)
        public String mDirectory;
        public boolean mHasVariations;

        @XmlElement(name = "Variables")
        public List<VariableDeclaration> mVariables = new ArrayList<VariableDeclaration>();

        @XmlElement(name = "Imports")
        public List<NameTypeLocation> mImports = new ArrayList<NameTypeLocation>();

        @XmlElementWrapper(name = "Targets")
        @XmlElement(name = "Target")
        public List<BindingTargetBundle> mBindingTargetBundles =
                new ArrayList<BindingTargetBundle>();

        @XmlAttribute(name = "isMerge", required = true)
        private boolean mIsMerge;

        // In order to be backwards compatible this property is not required and has a default which
        // enables data binding. Only new versions will potentially set and persist false values.
        @XmlAttribute(name = "isBindingData")
        private boolean mIsBindingData = true;

        // In order to be backwards compatible this property is not required and has a default
        // which is historically accurate. Only new versions will set and persist real values.
        @NonNull
        @XmlAttribute(name = "rootNodeType")
        private String mRootNodeViewType = "android.view.View";

        @Nullable
        @XmlAttribute(name = "rootNodeViewId")
        private String mRootNodeViewId;

        private LocationScopeProvider mClassNameLocationProvider;

        // for XML binding
        public LayoutFileBundle() {
        }

        /**
         * Updates configuration fields from the given bundle but does not change variables,
         * binding expressions etc.
         */
        public void inheritConfigurationFrom(LayoutFileBundle other) {
            mFileName = other.mFileName;
            mModulePackage = other.mModulePackage;
            mBindingClass = other.mBindingClass;
            mFullBindingClass = other.mFullBindingClass;
            mBindingClassName = other.mBindingClassName;
            mBindingPackage = other.mBindingPackage;
            mHasVariations = other.mHasVariations;
            mIsMerge = other.mIsMerge;
            mIsBindingData = other.mIsBindingData;
            mRootNodeViewType = other.mRootNodeViewType;
            mRootNodeViewId = other.mRootNodeViewId;
        }

        public LayoutFileBundle(@NonNull RelativizableFile file, @NonNull String fileName,
                @NonNull String directory, @NonNull String modulePackage, boolean isMerge,
                boolean isBindingData, @NonNull String rootViewType, @Nullable String rootViewId) {
            // We prefer relative path over absolute path as we don't want to break caching across
            // machines---see bug 121288180.
            if (file.getRelativeFile() != null) {
                mFilePath = file.getRelativeFile().getPath();
            } else {
                mFilePath = file.getAbsoluteFile().getPath();
            }
            mFileName = fileName;
            mDirectory = directory;
            mModulePackage = modulePackage;
            mIsMerge = isMerge;
            mIsBindingData = isBindingData;
            mRootNodeViewType = qualifyViewNodeName(rootViewType);
            mRootNodeViewId = rootViewId;
        }

        public LocationScopeProvider getClassNameLocationProvider() {
            if (mClassNameLocationProvider == null && mClassNameLocation != null
                    && mClassNameLocation.isValid()) {
                mClassNameLocationProvider = mClassNameLocation.createScope();
            }
            return mClassNameLocationProvider;
        }

        public void addVariable(String name, String type, Location location, boolean declared) {
            Preconditions.check(!NameTypeLocation.contains(mVariables, name),
                    "Cannot use same variable name twice. %s in %s", name, location);
            mVariables.add(new VariableDeclaration(name, type, location, declared));
        }

        public void addImport(String alias, String type, Location location) {
            Preconditions.check(!NameTypeLocation.contains(mImports, alias),
                    "Cannot import same alias twice. %s in %s", alias, location);
            mImports.add(new NameTypeLocation(alias, type, location));
        }

        public BindingTargetBundle createBindingTarget(String id, String viewName,
                boolean used, String tag, String originalTag, Location location) {
            BindingTargetBundle target = new BindingTargetBundle(id, viewName, used, tag,
                    originalTag, location);
            mBindingTargetBundles.add(target);
            return target;
        }

        public boolean isEmpty() {
            return mVariables.isEmpty() && mImports.isEmpty() && mBindingTargetBundles.isEmpty();
        }

        public BindingTargetBundle getBindingTargetById(String key) {
            for (BindingTargetBundle target : mBindingTargetBundles) {
                if (key.equals(target.mId)) {
                    return target;
                }
            }
            return null;
        }

        public String getFileName() {
            return mFileName;
        }

        public String getConfigName() {
            return mConfigName;
        }

        public String getDirectory() {
            return mDirectory;
        }

        public boolean hasVariations() {
            return mHasVariations;
        }

        public List<VariableDeclaration> getVariables() {
            return mVariables;
        }

        public List<NameTypeLocation> getImports() {
            return mImports;
        }

        public boolean isMerge() {
            return mIsMerge;
        }

        public boolean isBindingData() {
            return mIsBindingData;
        }

        @NonNull
        public String getRootNodeViewType() {
            return mRootNodeViewType;
        }

        @Nullable
        public String getRootNodeViewId() {
            return mRootNodeViewId;
        }

        public String getBindingClassName() {
            if (mBindingClassName == null) {
                String fullClass = getFullBindingClass();
                int dotIndex = fullClass.lastIndexOf('.');
                mBindingClassName = fullClass.substring(dotIndex + 1);
            }
            return mBindingClassName;
        }

        public void setBindingClass(String bindingClass, Location location) {
            mBindingClass = bindingClass;
            mClassNameLocation = location;
        }

        public String getBindingClassPackage() {
            if (mBindingPackage == null) {
                String fullClass = getFullBindingClass();
                int dotIndex = fullClass.lastIndexOf('.');
                mBindingPackage = fullClass.substring(0, dotIndex);
            }
            return mBindingPackage;
        }

        public String getFullBindingClass() {
            if (mFullBindingClass == null) {
                if (mBindingClass == null) {
                    mFullBindingClass = getModulePackage() + ".databinding." +
                            ParserHelper.toClassName(getFileName()) + "Binding";
                } else if (mBindingClass.startsWith(".")) {
                    mFullBindingClass = getModulePackage() + mBindingClass;
                } else if (mBindingClass.indexOf('.') < 0) {
                    mFullBindingClass = getModulePackage() + ".databinding." + mBindingClass;
                } else {
                    mFullBindingClass = mBindingClass;
                }
            }
            return mFullBindingClass;
        }

        public String createImplClassNameWithConfig() {
            return getBindingClassName() + getConfigName() + "Impl";
        }

        public List<BindingTargetBundle> getBindingTargetBundles() {
            return mBindingTargetBundles;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            LayoutFileBundle bundle = (LayoutFileBundle) o;
            return Objects.equals(mConfigName, bundle.mConfigName)
                    && Objects.equals(mDirectory, bundle.mDirectory)
                    && Objects.equals(mFileName, bundle.mFileName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFileName, mConfigName, mDirectory);
        }

        @Override
        public String toString() {
            return "LayoutFileBundle{" +
                    "mHasVariations=" + mHasVariations +
                    ", mDirectory='" + mDirectory + '\'' +
                    ", mConfigName='" + mConfigName + '\'' +
                    ", mModulePackage='" + mModulePackage + '\'' +
                    ", mFileName='" + mFileName + '\'' +
                    '}';
        }

        public String getModulePackage() {
            return mModulePackage;
        }

        /**
         * Returns the path to the original layout file. It could be an absolute path or a relative
         * path.
         */
        @NonNull
        public String getFilePath() {
          return mFilePath;
        }

        @Override
        public String provideScopeFilePath() {
            return getFilePath();
        }

        /*private static final Marshaller sMarshaller;
        private static final Unmarshaller sUnmarshaller;*/

        /*static {
            try {
                JAXBContext context = jaxbContext(LayoutFileBundle.class);
                sMarshaller = context.createMarshaller();
                sMarshaller.setProperty(Marshaller.JAXB_ENCODING, "utf-8");
                sUnmarshaller = context.createUnmarshaller();
            } catch (JAXBException e) {
                throw new RuntimeException("Cannot create the xml marshaller", e);
            }
        }*/
/*
        private static JAXBContext jaxbContext(Class<?> clazz) throws JAXBException {
            try {
                return (JAXBContext)
                        Class.forName("com.sun.xml.bind.v2.ContextFactory")
                                .getMethod("createContext", Class[].class, Map.class)
                                .invoke(null, new Class[] {clazz}, Collections.emptyMap());
            } catch (ClassNotFoundException e) {
                return JAXBContext.newInstance(clazz);
            } catch (ReflectiveOperationException e) {
                throw new LinkageError(e.getMessage(), e);
            }
        }*/

        /*public String toXML() throws JAXBException {
            StringWriter writer = new StringWriter();
            synchronized (sMarshaller) {
                sMarshaller.marshal(this, writer);
                return writer.getBuffer().toString();
            }
        }

        public static LayoutFileBundle fromXML(InputStream inputStream)
                throws JAXBException {
            synchronized (sUnmarshaller) {
                return (LayoutFileBundle) sUnmarshaller.unmarshal(inputStream);
            }
        }*/

        public String createTag() {
            return getDirectory() + "/" + getFileName();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static class NameTypeLocation {
        @XmlAttribute(name = "type", required = true)
        public String type;

        @XmlAttribute(name = "name", required = true)
        public String name;

        @XmlElement(name = "location", required = false)
        public Location location;

        public NameTypeLocation() {
        }

        public NameTypeLocation(String name, String type, Location location) {
            this.type = type;
            this.name = name;
            this.location = location;
        }

        @Override
        public String toString() {
            return "{" +
                    "type='" + type + '\'' +
                    ", name='" + name + '\'' +
                    ", location=" + location +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            NameTypeLocation that = (NameTypeLocation) o;
            return Objects.equals(location, that.location)
                    && name.equals(that.name)
                    && type.equals(that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, name, location);
        }

        public static boolean contains(List<? extends NameTypeLocation> list, String name) {
            for (NameTypeLocation ntl : list) {
                if (name.equals(ntl.name)) {
                    return true;
                }
            }
            return false;
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static class VariableDeclaration extends NameTypeLocation {
        @XmlAttribute(name = "declared", required = false)
        public boolean declared;

        public VariableDeclaration() {

        }

        public VariableDeclaration(String name, String type, Location location, boolean declared) {
            super(name, type, location);
            this.declared = declared;
        }
    }

    public static class MarshalledMapType {
        public List<NameTypeLocation> entries;
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static class BindingTargetBundle implements Serializable, LocationScopeProvider {
        // public for XML serialization

        @XmlAttribute(name = "id")
        public String mId;
        @XmlAttribute(name = "tag", required = true)
        public String mTag;
        @XmlAttribute(name = "originalTag")
        public String mOriginalTag;
        @XmlAttribute(name = "view", required = false)
        public String mViewName;
        private String mFullClassName;
        public boolean mUsed = true;
        @XmlElementWrapper(name = "Expressions")
        @XmlElement(name = "Expression")
        public List<BindingBundle> mBindingBundleList = new ArrayList<BindingBundle>();
        @XmlAttribute(name = "include")
        public String mIncludedLayout;
        @XmlElement(name = "location")
        public Location mLocation;
        private String mInterfaceType;
        private String mModulePackage;

        // For XML serialization
        public BindingTargetBundle() {
        }

        public BindingTargetBundle(String id, String viewName, boolean used,
                String tag, String originalTag, Location location) {
            mId = id;
            mViewName = viewName;
            mUsed = used;
            mTag = tag;
            mOriginalTag = originalTag;
            mLocation = location;
        }

        public void addBinding(String name, String expr, boolean isTwoWay, Location location,
                Location valueLocation) {
            mBindingBundleList.add(
                    new BindingBundle(name, expr, isTwoWay, location, valueLocation));
        }

        public void setIncludedLayout(String includedLayout) {
            mIncludedLayout = includedLayout;
        }

        public String getIncludedLayout() {
            return mIncludedLayout;
        }

        public String getViewName() {
            return mViewName;
        }

        public boolean isBinder() {
            return mIncludedLayout != null && (!"android.view.View".equals(mInterfaceType)
                    || !"android.view.View".equals(mViewName));
        }

        public void setInterfaceType(String interfaceType) {
            setInterfaceType(interfaceType, null);
        }

        public void setInterfaceType(String interfaceType, @Nullable String modulePackage) {
            mInterfaceType = interfaceType;
            mModulePackage = modulePackage;
        }

        /**
         * where this binding target is coming from, if it is a binding
         */
        @Nullable
        public String getModulePackage() {
            return mModulePackage;
        }

        public void setLocation(Location location) {
            mLocation = location;
        }

        public Location getLocation() {
            return mLocation;
        }

        public String getId() {
            return mId;
        }

        public String getTag() {
            return mTag;
        }

        public String getOriginalTag() {
            return mOriginalTag;
        }

        public String getFullClassName() {
            if (mFullClassName == null) {
                if (isBinder()) {
                    mFullClassName = mInterfaceType;
                } else {
                    mFullClassName = qualifyViewNodeName(mViewName);
                }
            }
            if (mFullClassName == null) {
                L.e("Unexpected full class name = null. view = %s, interface = %s, layout = %s",
                        mViewName, mInterfaceType, mIncludedLayout);
            }
            return mFullClassName;
        }

        public boolean isUsed() {
            return mUsed;
        }

        public List<BindingBundle> getBindingBundleList() {
            return mBindingBundleList;
        }

        public String getInterfaceType() {
            return mInterfaceType;
        }

        @Override
        public List<Location> provideScopeLocation() {
            return mLocation == null ? null : Collections.singletonList(mLocation);
        }

        @XmlAccessorType(XmlAccessType.NONE)
        public static class BindingBundle implements Serializable {

            private String mName;
            private String mExpr;
            private Location mLocation;
            private Location mValueLocation;
            private boolean mIsTwoWay;

            public BindingBundle() {
            }

            public BindingBundle(String name, String expr, boolean isTwoWay, Location location,
                    Location valueLocation) {
                mName = name;
                mExpr = expr;
                mLocation = location;
                mIsTwoWay = isTwoWay;
                mValueLocation = valueLocation;
            }

            @XmlAttribute(name = "attribute", required = true)
            public String getName() {
                return mName;
            }

            @XmlAttribute(name = "text", required = true)
            public String getExpr() {
                return mExpr;
            }

            public void setName(String name) {
                mName = name;
            }

            public void setExpr(String expr) {
                mExpr = expr;
            }

            public void setTwoWay(boolean isTwoWay) {
                mIsTwoWay = isTwoWay;
            }

            @XmlElement(name = "Location")
            public Location getLocation() {
                return mLocation;
            }

            public void setLocation(Location location) {
                mLocation = location;
            }

            @XmlElement(name = "ValueLocation")
            public Location getValueLocation() {
                return mValueLocation;
            }

            @XmlElement(name = "TwoWay")
            public boolean isTwoWay() {
                return mIsTwoWay;
            }

            public void setValueLocation(Location valueLocation) {
                mValueLocation = valueLocation;
            }
        }
    }

    /**
     * Just an inner callback class to process imports and variables w/ the same code.
     */
    private interface ValidateAndFilterCallback {
        List<? extends NameTypeLocation> get(LayoutFileBundle bundle);
    }

    /**
     * Information about an included layout.
     */
    public static class IncludedLayout {
        public final String layoutName;
        public final String modulePackage;
        public final String interfaceQName;


        private IncludedLayout(String layoutName, String modulePackage,
            String interfaceQName) {
            this.layoutName = layoutName;
            this.modulePackage = modulePackage;
            this.interfaceQName = interfaceQName;
        }

        private static class Builder {
            private String mLayoutName;
            private String mModulePackage;
            private String mInterfaceQName;

            Builder layoutName(String layoutName) {
                mLayoutName = layoutName;
                return this;
            }

            Builder modulePackage(String modulePackage) {
                mModulePackage = modulePackage;
                return this;
            }

            Builder interfaceQName(String interfaceQName) {
                mInterfaceQName = interfaceQName;
                return this;
            }

            public IncludedLayout build() {
                return new IncludedLayout(
                    mLayoutName,
                    mModulePackage,
                    mInterfaceQName
                );
            }
        }
    }
}
