package com.tyron.editor.snippet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A snippet object that can be inserted on the editor
 */
public class Snippet {

    private String mName;

    private String[] mPrefix;

    private Body mBody;

    private String mDescription;

    public String getName() {
        return mName;
    }

    /**
     * The prefixes that will be used to show this snippet on the code completion list
     * @return the array of snippet names
     */
    public String[] getPrefix() {
        return mPrefix;
    }

    public Body getBody() {
        return mBody;
    }

    /**
     * Retrieve the description of this snippet
     * @return The description
     */
    public String getDescription() {
        return mDescription;
    }

    public Snippet copy() {
        Snippet snippet = new Snippet();
        snippet.mName = this.mName;
        snippet.mBody = this.mBody.copy();
        snippet.mDescription = this.mDescription;
        snippet.mPrefix = this.mPrefix;
        return snippet;
    }

    public interface Text {
        String getText(Body body);
    }

    public static class Body {
        private Map<String, Variable> mVariables;
        private List<Text> mInsertTexts;

        public Body(Map<String, Variable> variables, List<Text> insertTexts) {
            mVariables = variables;
            mInsertTexts = insertTexts;
        }

        public Variable getVariable(String name) {
            return mVariables.get(name);
        }

        public List<Text> getInsertTexts() {
            return mInsertTexts;
        }

        public Body copy() {
            return new Body(new HashMap<>(mVariables), new ArrayList<>(mInsertTexts));
        }

        public Map<String, Variable> getVariables() {
            return mVariables;
        }
    }

    public static class NormalText implements Text {

        private final String mText;

        public NormalText(String text) {
            mText = text;
        }

        @Override
        public String getText(Body body) {
            return mText;
        }
    }

    public static final Snippet TEST;

    static {
        Snippet snippet = new Snippet();
        snippet.mName = "Indexed for loop";
        snippet.mDescription = "Indexed for loop";
        snippet.mPrefix = new String[]{"fori"};

        // for (int ${1:name}; ${name} < ${2:size}; ${name}++) { }

        Map<String, Variable> variableMap = new HashMap<>();
        variableMap.put("name", new Variable(1, "name", ""));
        variableMap.put("size", new Variable(2, "name", ""));

        List<Text> texts = new ArrayList<>();
        texts.add(new NormalText("for (int"));
        texts.add(variableMap.get("name"));
        texts.add(new NormalText("; "));
        texts.add(variableMap.get("size"));
        texts.add(new NormalText(" < "));
        texts.add(new Variable(-1,"name"));
        texts.add(new NormalText("++) { }"));
        snippet.mBody = new Body(variableMap, texts);

        TEST = snippet;
    }

    public static class Variable implements Text{
        private int mIndex;
        private String mName;
        private boolean mIsReference;
        private String mText;

        private int startLine;
        private int startColumn;

        public Variable(int index, String name) {
            mIndex = index;
            mName = name;
            mIsReference = true;
            mText = null;
        }

        public Variable(int index, String name, String text) {
            mIndex = index;
            mName = name;
            mIsReference = false;
            mText = text;
        }

        public int getStartLine() {
            return startLine;
        }

        public void setStartLine(int line) {
            this.startLine = line;
        }

        public int getStartColumn() {
            return this.startColumn;
        }

        public void setStartColumn(int column) {
            this.startColumn = column;
        }

        public int getIndex() {
            return mIndex;
        }

        private String getName() {
            return mName;
        }

        /**
         * @return Whether this variable references another variable defined in this Snippet
         */
        public boolean isReference() {
            return mIsReference;
        }

        @Override
        public String getText(Body body) {
            if (isReference()) {
                Variable variable = body.getVariable(getName());
                if (variable != null) {
                    return variable.getText(body);
                }
            }
            return mText;
        }
    }
}
