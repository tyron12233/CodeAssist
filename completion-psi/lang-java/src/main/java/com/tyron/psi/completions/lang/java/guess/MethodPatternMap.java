package com.tyron.psi.completions.lang.java.guess;


import java.util.HashMap;

class MethodPatternMap {
    private final HashMap<String, MethodPattern> myMethodNameToPatternsMap = new HashMap<>();

    public void addPattern(MethodPattern pattern){
        myMethodNameToPatternsMap.put(pattern.methodName + "#" + pattern.parameterCount, pattern);
    }

    public MethodPattern findPattern(String name, int parameterCount){
        return myMethodNameToPatternsMap.get(name + "#" + parameterCount);
    }
}
