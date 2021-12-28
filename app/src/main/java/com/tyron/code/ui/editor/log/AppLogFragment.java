package com.tyron.code.ui.editor.log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ui.editor.log.adapter.LogAdapter;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.provider.CompletionEngine;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.DiagnosticListener;
import org.openjdk.javax.tools.JavaFileObject;

import java.util.List;

public class AppLogFragment extends Fragment
        implements ProjectManager.OnProjectOpenListener {

    public static AppLogFragment newInstance(int id) {
        AppLogFragment fragment = new AppLogFragment();
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        fragment.setArguments(bundle);
        return fragment;
    }

    private int id;
    private MainViewModel mMainViewModel;
    private LogViewModel mModel;
    private LogAdapter mAdapter;
    private RecyclerView mRecyclerView;

    public AppLogFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = requireArguments().getInt("id");

        mModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout mRoot = new FrameLayout(requireContext());

        mAdapter = new LogAdapter();
        mAdapter.setListener(diagnostic -> {
            if (diagnostic.getSource() != null) {
                mMainViewModel.openFile(diagnostic.getSource());
            }
        });
        mRecyclerView = new RecyclerView(requireContext());
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);
        mRoot.addView(mRecyclerView,
                new FrameLayout.LayoutParams(-1, -1));
        return mRoot;
    }

    private BroadcastReceiver mLogReceiver;
    private DiagnosticListener<? super JavaFileObject> mDiagnosticListener;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mModel.getLogs(id).observe(getViewLifecycleOwner(), this::process);
        if (id == LogViewModel.APP_LOG) {
            ProjectManager.getInstance().addOnProjectOpenListener(this);
        } else if (id == LogViewModel.DEBUG) {
            ProjectManager.getInstance().addOnProjectOpenListener(this);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ProjectManager.getInstance().removeOnProjectOpenListener(this);
        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }
        if (mDiagnosticListener != null) {
            CompletionEngine.getInstance().removeDiagnosticListener(mDiagnosticListener);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mDiagnosticListener != null) {
            CompletionEngine.getInstance().removeDiagnosticListener(mDiagnosticListener);
            mDiagnosticListener = null;
        }
    }

    private void process(List<DiagnosticWrapper> texts) {
        mAdapter.submitList(texts);

        if (mRecyclerView.canScrollVertically(-1)) {
            mRecyclerView.scrollToPosition(mAdapter.getItemCount());
        }
    }

    @Override
    public void onProjectOpen(Project project) {
        Module module = project.getMainModule();
        if (id == LogViewModel.DEBUG) {
            if (module instanceof JavaModule) {
                mDiagnosticListener = d -> {
                    if (getActivity() == null) {
                        return;
                    }
                    JavaCompilerProvider provider = CompilerService.getInstance()
                            .getIndex(JavaCompilerProvider.KEY);
                    List<Diagnostic<? extends JavaFileObject>> diagnostics =
                            provider.getCompiler(project, (JavaModule) module)
                                    .getDiagnostics();
                    requireActivity().runOnUiThread(() ->
                            mModel.updateLogs(id, diagnostics));
                };
                CompletionEngine.getInstance().addDiagnosticListener(mDiagnosticListener);
            }
            return;
        }

        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }

        if (module instanceof AndroidModule) {
            mLogReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String type = intent.getExtras().getString("type", "DEBUG");
                    String message = intent.getExtras().getString("message", "No message provided");
                    DiagnosticWrapper wrapped = ILogger.wrap(message);

                    switch (type) {
                        case "DEBUG":
                        case "INFO":
                            wrapped.setKind(Diagnostic.Kind.NOTE);
                            mModel.d(LogViewModel.APP_LOG, wrapped);
                            break;
                        case "ERROR":
                            wrapped.setKind(Diagnostic.Kind.ERROR);
                            mModel.e(LogViewModel.APP_LOG, wrapped);
                            break;
                        case "WARNING":
                            wrapped.setKind(Diagnostic.Kind.WARNING);
                            mModel.w(LogViewModel.APP_LOG, wrapped);
                            break;
                    }
                }
            };
            requireActivity().registerReceiver(mLogReceiver,
                    new IntentFilter(((AndroidModule) module).getPackageName() + ".LOG"));
        }
    }
}
