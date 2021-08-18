package com.tyron.code.model;

/**
 * Class that represents an edit that should be
 * applied on the target file
 */
public class TextEdit {
    
    // determines what line and column this edit should be applied
    public Position start;
    public Position end;
    
    // The String that will be applied on the file,
    // if this string is empty, the characters between the 
    // positions specified should be deleted.
    public String edit;
    
    public TextEdit(Position start, Position end, String edit) {
        this.start = start;
        this.end = end;
        this.edit = edit;
    }
}
