package com.tyron.completion.java.action.api;

import com.tyron.completion.java.rewrite.Rewrite;

public class Action {

    private final Rewrite mRewrite;

    private final String mGroupId;

    private final String mName;

    public Action(Rewrite rewrite, String groupId, String actionName) {
        mRewrite = rewrite;
        mGroupId = groupId;
        mName = actionName;
    }

    public Rewrite getRewrite() {
        return mRewrite;
    }

    public String getGroupId() {
        return mGroupId;
    }

    public String getName() {
        return mName;
    }
}
