package com.tyron.completion.xml.model;

import android.annotation.SuppressLint;

import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import java.io.File;
import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import kotlin.jvm.functions.Function2;

public class XmlCachedCompletion extends CachedCompletion {

    public static final int TYPE_ATTRIBUTE = 0;
    public static final int TYPE_ATTRIBUTE_VALUE = 1;
    public static final int TYPE_TAG = 2;

    private Function2<CompletionItem, String, Boolean> mFilter;
    private String mFilterPrefix;
    private int mCompletionType;

    public XmlCachedCompletion(File file, int line, int column, String prefix, CompletionList completionList) {
        super(file, line, column, prefix, completionList);
    }

    public void setFilter(Function2<CompletionItem, String, Boolean> predicate) {
        mFilter = predicate;
    }

    public void setFilterPrefix(String prefix) {
        mFilterPrefix = prefix;
    }

    public void setCompletionType(int type) {
        mCompletionType = type;
    }

    public int getCompletionType() {
        return mCompletionType;
    }


    @SuppressLint("NewApi")
    public CompletionList getCompletionList() {
        CompletionList original = super.getCompletionList();
        CompletionList completionList = new CompletionList();
        completionList.isIncomplete = original.isIncomplete;
        completionList.items = new ArrayList<>(original.items);
        if (mFilter != null) {
            completionList.items = completionList.items.stream()
                    .filter(it -> mFilter.invoke(it, mFilterPrefix))
                    .collect(Collectors.toList());
        }
        return completionList;
    }

    public String getFilterPrefix() {
        return mFilterPrefix;
    }
}
