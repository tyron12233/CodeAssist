package com.tyron.code.model;
import java.util.List;
import java.util.ArrayList;

public class CompletionItem {
    
    public enum Kind {
        IMPORT,
        NORMAL
    }
    
    // The string that would be shown to the user
    public String label;
    
    public String detail;
    
    public String commitText;
    
    public Kind kind = Kind.NORMAL;
    
    public int cursorOffset = -1;
    
    public List<TextEdit> additionalTextEdits;
    
    public CompletionItem() {
        
    }
    
    public CompletionItem(String label) {
        this.label = label;
    }
    
    @Override
    public String toString() {
        return label;
    }
    
}
