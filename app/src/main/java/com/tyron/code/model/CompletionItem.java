package com.tyron.code.model;

public class CompletionItem {
    
    public String label;
    
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
