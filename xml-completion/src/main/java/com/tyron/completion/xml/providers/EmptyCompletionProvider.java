package com.tyron.completion.xml.providers;

import com.google.common.collect.ListMultimap;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.compiler.symbol.SymbolLoader;
import com.tyron.builder.compiler.symbol.SymbolWriter;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.ResourceRepository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import kotlin.io.FilesKt;

/**
 * Only used to listen to updates to files.
 */
public class EmptyCompletionProvider extends CompletionProvider {
    @Override
    public boolean accept(File file) {
        return "xml".equals(FilesKt.getExtension(file));
    }

    @Override
    public CompletionList complete(CompletionParameters parameters) {
        try {
            doRefresh(parameters);
        } catch (IOException e) {
            // ignored
        }
        return null;
    }

    private void doRefresh(CompletionParameters parameters) throws IOException {
        File file = parameters.getFile();
        XmlRepository repository = XmlRepository.getRepository(parameters.getProject(),
                                                               (AndroidModule) parameters.getModule());
        repository.getRepository().updateFile(file, parameters.getContents());
        createSymbols(repository);
    }

    private void createSymbols(XmlRepository xmlRepository) throws IOException {
        ResourceRepository repository = xmlRepository.getRepository();
        List<ResourceType> resourceTypes =
                repository.getResourceTypes();
        int id = 0;

        List<SymbolLoader.SymbolEntry> entries = new ArrayList<>();
        for (ResourceType resourceType : resourceTypes) {
            ListMultimap<String, ResourceItem> resources =
                    repository.getResources(repository.getNamespace(), resourceType);
            if (resources.values().isEmpty()) {
                continue;
            }
            for (Map.Entry<String, ResourceItem> resourceItemEntry : resources.entries()) {
                ResourceItem value = resourceItemEntry.getValue();
                SymbolLoader.SymbolEntry entry = new SymbolLoader.SymbolEntry(value.getName(),
                                                                              resourceType.getName(),
                                                                              String.valueOf(id));
                entries.add(entry);
                id++;
            }
        }

        SymbolLoader loader = new SymbolLoader(entries);
        SymbolWriter symbolWriter = new SymbolWriter(null, "com.tyron.code", loader, null);
        symbolWriter.addSymbolsToWrite(loader);
        IdeLog.getLogger().info("Writings symbols: \n" +
                                symbolWriter.getString());
    }
}
