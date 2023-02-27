package org.jetbrains.kotlin.com.intellij.openapi.roots.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleImpl;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleFileIndex;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ModuleRootManager;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEntry;
import org.jetbrains.kotlin.com.intellij.openapi.roots.OrderEnumerator;
import org.jetbrains.kotlin.com.intellij.openapi.roots.RootPolicy;
import org.jetbrains.kotlin.com.intellij.openapi.roots.SourceFolder;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem;
import org.jetbrains.kotlin.com.intellij.sdk.Sdk;
import org.jetbrains.kotlin.com.intellij.sdk.SdkManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ModuleRootManagerImpl extends ModuleRootManager {

    private static final String MODULE_DIR = "$MODULE_DIR$";
    public static final Key<JSONObject> JSON_OBJECT_KEY = Key.create("MODULE_OPTIONS_KEY");

    private final Module module;
    private final JSONObject options;
    private final ModuleFileIndexImpl moduleFileIndex;

    private List<ContentEntry> contentEntries = new ArrayList<>();
    private List<OrderEntry> orderEntries = new ArrayList<>();

    public ModuleRootManagerImpl(Module module) {
        options = module.getUserData(JSON_OBJECT_KEY);
        assert options != null;

        this.module = module;
        moduleFileIndex = new ModuleFileIndexImpl(module);

        String modulePath = ((ModuleImpl) module).getFilePath();
        VirtualFileSystem local = StandardFileSystems.local();
        try {
            JSONArray contents = options.getJSONArray("contents");
            for (int i = 0; i < contents.length(); i++) {
                JSONObject jsonObject = contents.getJSONObject(i);
                String url = jsonObject.getString("url");
                url = url.replace(MODULE_DIR, modulePath);
                VirtualFile contentRoot = local.findFileByPath(url);

                List<SourceFolder> sourceFolders = new ArrayList<>();
                ContentEntryBridge contentEntryBridge =
                        new ContentEntryBridge(this, contentRoot, sourceFolders);

                JSONArray sourceFoldersJsonObject = jsonObject.getJSONArray("sourceFolders");
                for (int j = 0; j < sourceFoldersJsonObject.length(); j++) {
                    JSONObject sourceFolder = sourceFoldersJsonObject.getJSONObject(j);
                    String sourceFolderUrl = sourceFolder.getString("url");
                    sourceFolderUrl = sourceFolderUrl.replace(MODULE_DIR, modulePath);

                    VirtualFile sourceFolderVirtualFile = local.findFileByPath(sourceFolderUrl);
                    sourceFolders.add(new ReadOnlySourceFolder(contentEntryBridge,
                            sourceFolderVirtualFile,
                            false));
                }
                contentEntries.add(contentEntryBridge);
            }

            // orderEntries
            JSONArray orderEntriesJsonArray = options.getJSONArray("orderEntries");
            for (int i = 0; i < orderEntriesJsonArray.length(); i++) {
                JSONObject orderEntryJsonObject = orderEntriesJsonArray.getJSONObject(i);
                String type = orderEntryJsonObject.getString("type");

                if ("sourceFolder".equals(type)) {
                    orderEntries.add(new ReadOnlyModuleSourceOrderEntry(this, false));
                } else if ("inheritedJdk".equals(type)) {
                    SdkManager instance =
                            SdkManager.getInstance(((ModuleImpl) module).getProject());
                    Sdk defaultSdk = instance.getDefaultSdk();
                    orderEntries.add(new ReadOnlyInheritedJdkOrderEntry(this, defaultSdk));
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public @NotNull ModuleFileIndex getFileIndex() {
        return moduleFileIndex;
    }

    @Override
    public Module[] getDependencies() {
        return getDependencies(false);
    }

    @Override
    public Module[] getDependencies(boolean includeTests) {
        return new Module[0];
    }

    @Override
    public boolean isDependsOn(@NonNull Module module) {
        return false;
    }

    @NonNull
    @Override
    public Module getModule() {
        return module;
    }

    @Override
    public ContentEntry[] getContentEntries() {
        return contentEntries.toArray(ContentEntry[]::new);
    }

    @Override
    public OrderEntry[] getOrderEntries() {
        return orderEntries.toArray(OrderEntry.EMPTY_ARRAY);
    }

    @Nullable
    @Override
    public Sdk getSdk() {
        return null;
    }

    @Override
    public boolean isSdkInherited() {
        return false;
    }

    @NonNull
    @Override
    public VirtualFile[] getContentRoots() {
        return Arrays.stream(getContentEntries())
                .map(ContentEntry::getFile)
                .toArray(VirtualFile[]::new);
    }

    @NonNull
    @Override
    public String[] getContentRootUrls() {
        return Arrays.stream(getContentRoots()).map(VirtualFile::getUrl).toArray(String[]::new);
    }

    @NonNull
    @Override
    public VirtualFile[] getExcludeRoots() {
        return new VirtualFile[0];
    }

    @NonNull
    @Override
    public String[] getExcludeRootUrls() {
        return new String[0];
    }

    @NonNull
    @Override
    public VirtualFile[] getSourceRoots() {
        return getSourceRoots(false);
    }

    @NonNull
    @Override
    public VirtualFile[] getSourceRoots(boolean includingTests) {
        return Arrays.stream(getContentEntries())
                .flatMap(it -> Arrays.stream(it.getSourceFolders()))
                .filter(it -> includingTests == it.isTestSource())
                .map(SourceFolder::getFile)
                .toArray(VirtualFile[]::new);
    }

    @NonNull
    @Override
    public String[] getSourceRootUrls() {
        return getSourceRootUrls(false);
    }

    @NonNull
    @Override
    public String[] getSourceRootUrls(boolean includingTests) {
        return Arrays.stream(getSourceRoots(includingTests))
                .map(VirtualFile::getUrl)
                .toArray(String[]::new);
    }

    @Override
    public <R> R processOrder(@NonNull RootPolicy<R> policy, R initialValue) {
        return orderEntries().process(policy, initialValue);
    }

    @NonNull
    @Override
    public OrderEnumerator orderEntries() {
        return new ModuleOrderEnumerator(this, null);
    }

    @NonNull
    @Override
    public String[] getDependencyModuleNames() {
        return new String[0];
    }

    @Override
    public <T> T getModuleExtension(@NonNull Class<T> klass) {
        return null;
    }

    @NonNull
    @Override
    public Module[] getModuleDependencies() {
        return new Module[0];
    }

    @NonNull
    @Override
    public Module[] getModuleDependencies(boolean includeTests) {
        return new Module[0];
    }
}
