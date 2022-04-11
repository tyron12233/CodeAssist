package com.tyron.builder.api.execution;


import com.tyron.builder.api.internal.exceptions.DefaultMultiCauseException;

import java.util.Collection;
import java.util.List;

public class MultipleBuildFailures extends DefaultMultiCauseException {
    public MultipleBuildFailures(Collection<? extends Throwable> causes) {
        super("Build completed with " + causes.size() + " failures.", causes);
    }

    public void replaceCauses(List<? extends Throwable> causes) {
        super.initCauses(causes);
    }
}