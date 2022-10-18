package com.tyron.completion.xml.task;

import androidx.annotation.NonNull;

import com.android.ide.common.rendering.api.AttrResourceValue;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.rendering.api.StyleableResourceValue;
import com.android.ide.common.resources.ResourceItem;
import com.android.resources.ResourceType;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Table;
import com.tyron.builder.compiler.symbol.SymbolLoader;
import com.tyron.builder.compiler.symbol.SymbolWriter;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.v2.project.LocalResourceRepository;
import com.tyron.completion.xml.v2.project.ResourceRepositoryManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Used to create fake R.java files from the project resources for it to
 * show up on code completion. Files generated from this task should not
 * be included in the compilation process as the values of the fields are
 * not accurate from what AAPT2 generates.
 */
public class InjectResourcesTask {

    public static void inject(@NonNull Project project) {
        try {
            inject(project, (AndroidModule) project.getMainModule());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void inject(@NonNull Project project,
                              @NonNull AndroidModule module) throws IOException {
        CompilationInfo compilationInfo = CompilationInfo.get(module);
        if (compilationInfo == null) {
            return;
        }

        InjectResourcesTask task = new InjectResourcesTask(project, module);
        task.inject(resourceFile -> {
            if (project.isCompiling() || project.isIndexing()) {
                return;
            }
            SourceFileObject sourceFileObject =
                    new SourceFileObject(resourceFile.toPath(), module, Instant.now());
            compilationInfo.update(sourceFileObject);
        });
    }

    private final AndroidModule mModule;
    private final Project mProject;

    public InjectResourcesTask(Project project, AndroidModule module) {
        mProject = project;
        mModule = module;
    }

    public void inject(Consumer<File> consumer) throws IOException {
        ResourceRepositoryManager instance = ResourceRepositoryManager.getInstance(mModule);
        LocalResourceRepository appResources = instance.getAppResources();

        String classContents = createSymbols(appResources);

        File classFile = getOrCreateResourceClass(mModule);

        FileUtils.writeStringToFile(classFile, classContents, StandardCharsets.UTF_8);

        consumer.accept(classFile);
    }


    private String createSymbols(LocalResourceRepository repository) throws IOException {

        int id = 0;

        Table<String, String, SymbolLoader.SymbolEntry> symbols = HashBasedTable.create();


        Set<com.android.ide.common.rendering.api.ResourceNamespace> namespaces =
                repository.getNamespaces();
        for (com.android.ide.common.rendering.api.ResourceNamespace namespace : namespaces) {
            Set<ResourceType> resourceTypes = repository.getResourceTypes(namespace);
            for (ResourceType resourceType : resourceTypes) {
                if (!resourceType.getCanBeReferenced() && resourceType != ResourceType.STYLEABLE) {
                    continue;
                }

                ListMultimap<String, ResourceItem> resources =
                        repository.getResources(namespace, resourceType);

                if (resources.values().isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, ResourceItem> resourceItemEntry : resources.entries()) {
                    addResource(id,
                            namespace,
                            symbols,
                            resourceType,
                            resourceItemEntry);
                    id++;
                }

            }

        }

        SymbolLoader loader = new SymbolLoader(symbols);
        SymbolWriter symbolWriter = new SymbolWriter(null, mModule.getPackageName(), loader, null);
        symbolWriter.addSymbolsToWrite(loader);
        return symbolWriter.getString();
    }

    private void addResource(int id,
                             ResourceNamespace namespace,
                             Table<String, String, SymbolLoader.SymbolEntry> symbols,
                             ResourceType resourceType,
                             Map.Entry<String, ResourceItem> resourceItemEntry) {
        if (resourceType == ResourceType.STYLEABLE) {
            addStyleableResource(id, namespace, symbols, resourceItemEntry);
            return;
        }
        ResourceItem value = resourceItemEntry.getValue();
        String replacedName = convertName(value.getName());
        SymbolLoader.SymbolEntry entry = new SymbolLoader.SymbolEntry(replacedName,
                getType(resourceType),
                String.valueOf(id));
        symbols.put(resourceType.getName(), replacedName, entry);
    }

    private void addStyleableResource(int id,
                                      ResourceNamespace namespace,
                                      Table<String, String, SymbolLoader.SymbolEntry> symbols,
                                      Map.Entry<String, ResourceItem> resourceItemEntry) {
        ResourceItem value = resourceItemEntry.getValue();
        if (!(value.getResourceValue() instanceof StyleableResourceValue)) {
            return;
        }
        StyleableResourceValue styleable = ((StyleableResourceValue) value.getResourceValue());
        String valueItem = "new int[" + styleable.getAllAttributes().size() + "];";
        String replacedName = convertName(value.getName());
        SymbolLoader.SymbolEntry entry =
                new SymbolLoader.SymbolEntry(replacedName, "int[]", valueItem);
        symbols.put(ResourceType.STYLEABLE.getName(), replacedName, entry);

        for (AttrResourceValue attr : styleable.getAllAttributes()) {
            String name = attr.getName();
            if (name.isEmpty()) {
                continue;
            }

            String replace = name.replace(':', '_');
            String attrName = replacedName + (replace.isEmpty() ? "" : "_" + replace);
            SymbolLoader.SymbolEntry attrEntry = new SymbolLoader.SymbolEntry(attrName, "int", "0");
            symbols.put(ResourceType.STYLEABLE.getName(), attrName, attrEntry);
        }
    }

    private static String getType(ResourceType type) {
        return "int";
    }

    private static String convertName(String name) {
        if (!name.contains(".")) {
            return name;
        }
        return name.replace('.', '_');
    }

    public static File getOrCreateResourceClass(AndroidModule module) throws IOException {
        File outputDirectory = new File(module.getBuildDirectory(), "injected/resource");
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Unable to create directory " + outputDirectory);
        }

        File classFile = new File(outputDirectory, "R.java");
        if (!classFile.exists() && !classFile.createNewFile()) {
            throw new IOException("Unable to create " + classFile);
        }
        return classFile;
    }
}
