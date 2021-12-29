package com.tyron.completion.java.action.api;

import com.tyron.completion.java.rewrite.Rewrite;

public class Action {

    private final Rewrite mRewrite;

    public Action(Rewrite rewrite) {
        mRewrite = rewrite;
    }

    public Rewrite getRewrite() {
        return mRewrite;
    }
}
