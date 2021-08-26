package com.tyron.code.model;

import androidx.annotation.NonNull;

import com.tyron.code.editor.drawable.CircleDrawable;

import java.util.List;

public class CompletionItem {
    
    public enum Kind {
		OVERRIDE,
        IMPORT,
        NORMAL
    }
    
    // The string that would be shown to the user
    public String label;
    
    public String detail;
    
    public String commitText;
    
    public Kind action = Kind.NORMAL;
    
    public CircleDrawable.Kind iconKind = CircleDrawable.Kind.Method;
    
    public int cursorOffset = -1;
    
    public List<TextEdit> additionalTextEdits;
    
	public String data;
	
    public CompletionItem() {
        
    }
    
    public CompletionItem(String label) {
        this.label = label;
    }
    
    @NonNull
    @Override
    public String toString() {
        return label;
    }
    
}
