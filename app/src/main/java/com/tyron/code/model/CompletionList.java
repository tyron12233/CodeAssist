package com.tyron.code.model;
import java.util.List;
import java.util.ArrayList;

public class CompletionList {

    public static final CompletionList EMPTY = new CompletionList();

    public boolean isIncomplete = false;
    
    public List<CompletionItem> items = new ArrayList<>();
}
