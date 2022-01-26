package com.tyron.kotlin_completion;

import com.tyron.actions.ActionManager;
import com.tyron.kotlin_completion.action.ImplementAbstractFunctionsQuickFix;

public class KotlinCompletionModule {

    public static void registerActions(ActionManager actionManager) {
        actionManager.registerAction(ImplementAbstractFunctionsQuickFix.ID, new ImplementAbstractFunctionsQuickFix());
    }
}
