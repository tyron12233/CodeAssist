package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.file.DuplicatesStrategy;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.tasks.WorkResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class DuplicateHandlingCopyActionDecorator implements CopyAction {

    private final static Logger LOGGER = LoggerFactory.getLogger(DuplicateHandlingCopyActionDecorator.class);
    private final CopyAction delegate;
    private final DocumentationRegistry documentationRegistry;

    public DuplicateHandlingCopyActionDecorator(CopyAction delegate, DocumentationRegistry documentationRegistry) {
        this.delegate = delegate;
        this.documentationRegistry = documentationRegistry;
    }

    @Override
    public WorkResult execute(final CopyActionProcessingStream stream) {
        final Set<RelativePath> visitedFiles = new HashSet<>();

        return delegate.execute(action -> stream.process(details -> {
            if (!details.isDirectory()) {
                DuplicatesStrategy strategy = details.getDuplicatesStrategy();
                RelativePath relativePath = details.getRelativePath();
                if (!visitedFiles.add(relativePath)) {
                    if (details.isDefaultDuplicatesStrategy()) {
                        failWithIncorrectDuplicatesStrategySetup(relativePath);
                    }
                    if (strategy == DuplicatesStrategy.EXCLUDE) {
                        return;
                    } else if (strategy == DuplicatesStrategy.FAIL) {
                        throw new BuildException(String.format("Encountered duplicate path \"%s\" during copy operation configured with DuplicatesStrategy.FAIL", details.getRelativePath()));
                    } else if (strategy == DuplicatesStrategy.WARN) {
                        LOGGER.warn("Encountered duplicate path \"" +  details.getRelativePath() + "\" during copy operation configured with DuplicatesStrategy.WARN");
                    }
                }
            }

            action.processFile(details);
        }));
    }

    private void failWithIncorrectDuplicatesStrategySetup(RelativePath relativePath) {
        throw new InvalidUserCodeException(
                "Entry " + relativePath.getPathString() + " is a duplicate but no duplicate handling strategy has been set. "
//                "Please refer to " + documentationRegistry.getDslRefForProperty(Copy.class, "duplicatesStrategy") + " for details."
        );
    }
}