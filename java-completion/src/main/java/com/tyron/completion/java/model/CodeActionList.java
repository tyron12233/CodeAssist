package com.tyron.completion.java.model;

import java.util.List;

/**
 * CLass that holds a list of {@link CodeAction} useful when
 * passing CodeActions with different categories
 *
 * eg.  Quick fixes
 *          - CodeAction
 *          - CodeActon
 *      Suggestions
 *          - CodeAction
 *          - CodeAction
 */
public class CodeActionList {

    private String title;

    private List<CodeAction> actions;

    public List<CodeAction> getActions() {
        return actions;
    }

    public void setActions(List<CodeAction> actions) {
        this.actions = actions;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
