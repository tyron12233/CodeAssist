package com.tyron.actions.impl;

import androidx.annotation.NonNull;

import com.tyron.actions.AnAction;
import com.tyron.actions.Presentation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public class PresentationFactory {

    private final Map<AnAction, Presentation> mPresentations = new WeakHashMap<>();
    private boolean mNeedRebuild;

    private static final Collection<PresentationFactory> sAllFactories = new ArrayList<>();

    public PresentationFactory() {
        sAllFactories.add(this);
    }

    public final Presentation getPresentation(@NonNull AnAction action) {
        Presentation presentation = mPresentations.get(action);
        if (presentation == null) {
            Presentation templatePresentation = action.getTemplatePresentation();
            presentation = templatePresentation.clone();
            presentation = mPresentations.putIfAbsent(action, presentation);

            processPresentation(Objects.requireNonNull(presentation));
        }
        return presentation;
    }

    protected void processPresentation(@NonNull Presentation presentation) {

    }

    public void reset() {
        mPresentations.clear();
    }

    public static void clearPresentationCaches() {
        for (PresentationFactory factory : sAllFactories) {
            factory.reset();
        }
    }
}
