package com.tyron.code.service;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.tyron.code.ApplicationLoader;
import com.tyron.common.SharedPreferenceKeys;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.util.ApkInstaller;

import javax.tools.Diagnostic;

import java.io.File;
import java.util.Objects;

public class CompilerServiceConnection implements ServiceConnection {

    private final MainViewModel mMainViewModel;
    private final LogViewModel mLogViewModel;

    private CompilerService mService;
    private BuildType mBuildType;
    private boolean mCompiling;

    public CompilerServiceConnection(MainViewModel mainViewModel, LogViewModel logViewModel) {
        mMainViewModel = mainViewModel;
        mLogViewModel = logViewModel;
    }

    public void setBuildType(BuildType type) {
        mBuildType = type;
    }

    public boolean isCompiling() {
        return mCompiling;
    }

    public void setShouldShowNotification(boolean val) {
        if (mService != null) {
            mService.setShouldShowNotification(val);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mService = ((CompilerService.CompilerBinder) binder).getCompilerService();
        if (mService == null) {
            mLogViewModel.e(LogViewModel.BUILD_LOG, "CompilerService is null!");
            return;
        }
        mService.setLogger(ILogger.wrap(mLogViewModel));
        mService.setShouldShowNotification(false);
        mService.setOnResultListener((success, message) -> {
            mMainViewModel.setCurrentState(null);
            mMainViewModel.setIndexing(false);

            if (success) {
                mLogViewModel.d(LogViewModel.BUILD_LOG, message);
                mLogViewModel.clear(LogViewModel.APP_LOG);

                File file = new File(ProjectManager.getInstance().getCurrentProject()
                        .getMainModule().getBuildDirectory(), "bin/signed.apk");
                if (file.exists() && mBuildType != BuildType.AAB) {
                     SharedPreferences preference = ApplicationLoader.getDefaultPreferences();
                     if (preference.getBoolean(SharedPreferenceKeys.INSTALL_APK_DIRECTLY, true)) {
                         ApkInstaller.installApplication(mService,file.getAbsolutePath());
                    } else {
                         mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                     }
                    DiagnosticWrapper wrapper = new DiagnosticWrapper();
                    wrapper.setKind(Diagnostic.Kind.NOTE);
                    wrapper.setMessage("Generated APK has been saved to " + file.getAbsolutePath());
                    wrapper.setExtra("INSTALL");
                    wrapper.setSource(file);
                    wrapper.setCode("");
                    wrapper.setOnClickListener((view) -> {
                        if (view == null || view.getContext() == null) {
                            return;
                        }
                        ApkInstaller.installApplication(view.getContext(), file.getAbsolutePath());
                    });
                    mLogViewModel.d(LogViewModel.BUILD_LOG, wrapper);
                }
            } else {
                mLogViewModel.e(LogViewModel.BUILD_LOG, message);
                if (BottomSheetBehavior.STATE_COLLAPSED ==
                        Objects.requireNonNull(mMainViewModel.getBottomSheetState().getValue())) {
                    mMainViewModel.setBottomSheetState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                }
            }
        });

        if (mBuildType != null) {
            mCompiling = true;
            mService.compile(ProjectManager.getInstance().getCurrentProject(), mBuildType);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mService = null;
        mMainViewModel.setCurrentState(null);
        mMainViewModel.setIndexing(false);
        mCompiling = false;
    }
}
