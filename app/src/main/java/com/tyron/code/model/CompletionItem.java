package com.tyron.code.model;
import java.util.List;
import java.util.ArrayList;

public class CompletionItem {
    
    // The string that would be shown to the user
    public String label;
    
    public String detail;
    
    public String commitText;
    
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
