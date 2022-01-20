package com.tyron.code.ui.main.action.compile;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tyron.actions.AnActionEvent;
import com.tyron.builder.compiler.BuildType;
import com.tyron.code.R;
import com.tyron.code.ui.main.CompileCallback;
import com.tyron.code.ui.main.MainFragment;

public class CompileReleaseAction extends CompileAction {

    public CompileReleaseAction() {
        super(BuildType.RELEASE);
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        CompileCallback callback = e.getData(MainFragment.COMPILE_CALLBACK_KEY);
        callback.compile(BuildType.RELEASE);
    }

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.action_menu_build_release);
    }
}
