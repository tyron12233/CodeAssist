package com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.constants;

import java.io.Serializable;
import java.util.Optional;

public class ConstantsAnalysisResult implements Serializable {

    private final ConstantToDependentsMappingBuilder constantToDependentsMappingBuilder;

    public ConstantsAnalysisResult() {
        this.constantToDependentsMappingBuilder = ConstantToDependentsMapping.builder();
    }

    public Optional<ConstantToDependentsMapping> getConstantToDependentsMapping() {
        return Optional.of(constantToDependentsMappingBuilder.build());
    }

    public void addPublicDependent(String constantOrigin, String constantDependent) {
        if (!constantOrigin.equals(constantDependent)) {
            constantToDependentsMappingBuilder.addAccessibleDependent(constantOrigin, constantDependent);
        }
    }

    public void addPrivateDependent(String constantOrigin, String constantDependent) {
        if (!constantOrigin.equals(constantDependent)) {
            constantToDependentsMappingBuilder.addPrivateDependent(constantOrigin, constantDependent);
        }
    }

}

