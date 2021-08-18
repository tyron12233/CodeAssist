package com.tyron.code.model;

/**
 * Represents the position in the editor in 
 * terms of lines and columns
 */
public class Position {
    
    public int line;
    
    public int column;
    
    public Position(int line, int column) {
        this.line = line;
        this.column = column;
    }
}
