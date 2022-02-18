package com.tyron.completion.xml.task;

import androidx.annotation.NonNull;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Table;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.compiler.symbol.SymbolLoader;
import com.tyron.builder.compiler.symbol.SymbolWriter;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.ResourceRepository;
import com.tyron.completion.xml.repository.api.ResourceNamespace;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Used to create fake R.java files from the project resources for it to
 * show up on code completion. Files generated from this task should not
 * be included in the compilation process as the values of the fields are
 * not accurate from what AAPT2 generates.
 */
public class InjectResourcesTask {

    private final AndroidModule mModule;
    private final Project mProject;

    public InjectResourcesTask(Project project, AndroidModule module) {
        mProject = project;
        mModule = module;
    }

    public void inject(Consumer<File> consumer) throws IOException {
        XmlRepository xmlRepository = XmlRepository.getRepository(mProject, mModule);
        String classContents = createSymbols(xmlRepository);

        File classFile = getOrCreateResourceClass(mModule);

        FileUtils.writeStringToFile(classFile, classContents, StandardCharsets.UTF_8);
        mModule.addInjectedClass(classFile);

        consumer.accept(classFile);
    }

    private String createSymbols(XmlRepository xmlRepository) throws IOException {
        ResourceRepository repository = xmlRepository.getRepository();
        List<ResourceType> resourceTypes = repository.getResourceTypes();
        int id = 0;

        Table<String, String, SymbolLoader.SymbolEntry> symbols = HashBasedTable.create();
        for (ResourceType resourceType : resourceTypes) {
            if (!resourceType.getCanBeReferenced()) {
                continue;
            }
            ListMultimap<String, ResourceItem> resources =
                    repository.getResources(repository.getNamespace(), resourceType);
            if (resources.values()
                    .isEmpty()) {
                continue;
            }
            for (Map.Entry<String, ResourceItem> resourceItemEntry : resources.entries()) {
                ResourceItem value = resourceItemEntry.getValue();
                SymbolLoader.SymbolEntry entry =
                        new SymbolLoader.SymbolEntry(value.getName(), getType(resourceType),
                                                     String.valueOf(id));
                symbols.put(resourceType.getName(), value.getName(), entry);
                id++;
            }
        }

        SymbolLoader loader = new SymbolLoader(symbols);
        SymbolWriter symbolWriter = new SymbolWriter(null, mModule.getPackageName(), loader, null);
        symbolWriter.addSymbolsToWrite(loader);
        return symbolWriter.getString();
    }

    private static String getType(ResourceType type) {
        return "int";
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
