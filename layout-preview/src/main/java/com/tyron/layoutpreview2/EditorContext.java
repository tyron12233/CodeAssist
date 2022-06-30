package com.tyron.layoutpreview2;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.tyron.xml.completion.repository.Repository;
import com.tyron.layoutpreview2.attr.AttributeApplier;
import com.tyron.layoutpreview2.view.EditorView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The main context that will be used for inflating the views.
 * Can be used to access resources from the editor.
 */
public class EditorContext extends ContextWrapper {

    private Repository mRepository;

    private final Map<String, String> mEditorViewMap = new HashMap<>();
    private final Set<AttributeApplier> mAttributeAppliers = new HashSet<>();

    public EditorContext(Context base) {
        super(base);
    }

    @NonNull
    public Repository getRepository() {
        return mRepository;
    }

    public void setRepository(@NonNull Repository repository) {
        mRepository = repository;
    }


    public void registerMapping(@NonNull Class<? extends View> clazz, @NonNull Class<? extends EditorView> editorClass) {
        registerMapping(clazz.getName(), editorClass.getName());
    }

    public void registerMapping(@NonNull String clazz, String editorClass) {
        final String put = mEditorViewMap.put(clazz, editorClass);
        if (put != null) {
            throw new IllegalArgumentException(clazz + " is already registered with value: " + put);
        }
    }

    public void registerAttributeApplier(@NonNull AttributeApplier applier) {
        final boolean contains = mAttributeAppliers.contains(applier);
        if (contains) {
            throw new IllegalArgumentException("Attribute applier already registered.");
        }
        mAttributeAppliers.add(applier);
    }

    public ImmutableSet<AttributeApplier> getAttributeAppliers() {
        return ImmutableSet.copyOf(mAttributeAppliers);
    }

    @Nullable
    public String getMapping(@NonNull String clazz) {
        return mEditorViewMap.get(clazz);
    }

    public static EditorContext getEditorContext(@NonNull Context base) {
        Context current = base;
        while (current instanceof ContextWrapper) {
            if (current instanceof EditorContext) {
                return ((EditorContext) current);
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        throw new IllegalArgumentException("Not an EditorContext");
    }
}
