package com.tyron.code.ui.editor.log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.tyron.ProjectManager;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.code.R;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;

import org.openjdk.javax.tools.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppLogFragment extends Fragment {

    public static AppLogFragment newInstance(int id) {
        AppLogFragment fragment = new AppLogFragment();
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        fragment.setArguments(bundle);
        return fragment;
    }

    private int id;
    private NestedScrollView mRoot;
    private TextView mLogView;
    private boolean mIgnoreProcess;

    private MainViewModel mMainViewModel;

    public AppLogFragment() {

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = requireArguments().getInt("id");

        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = new NestedScrollView(requireContext());
        mRoot.setFillViewport(true);

        mLogView = new TextView(requireContext());
        mLogView.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular));
        mLogView.setMovementMethod(LinkMovementMethod.getInstance());
        mLogView.setVerticalScrollBarEnabled(true);
        mLogView.setTextColor(0xffFFFFFF);

        mRoot.addView(mLogView, new FrameLayout.LayoutParams(-1, -1));
        return mRoot;
    }

    private BroadcastReceiver mLogReceiver;

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LogViewModel model = new ViewModelProvider(requireActivity())
                .get(LogViewModel.class);
        model.getLogs(id).observe(getViewLifecycleOwner(), this::process);

        if (id == LogViewModel.APP_LOG) {
            if (ProjectManager.getInstance().getCurrentProject() != null) {
                mLogReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String type = intent.getExtras().getString("type", "DEBUG");
                        String message = intent.getExtras().getString("message", "No message provided");
                        DiagnosticWrapper wrapped = ILogger.wrap(message);
                        switch (type) {
                            case "DEBUG":
                                model.d(LogViewModel.APP_LOG, wrapped);
                                break;
                            case "ERROR":
                                model.e(LogViewModel.APP_LOG, wrapped);
                                break;
                            case "WARNING":
                                model.w(LogViewModel.APP_LOG, wrapped);
                                break;
                        }
                    }
                };
                requireActivity().registerReceiver(mLogReceiver, new IntentFilter(ProjectManager.getInstance().getCurrentProject().getPackageName() + ".LOG"));
            }

            new Thread(() -> {
                while (true) {
                    Intent intent = new Intent("com.test.LOG");
                    intent.putExtra("type", "ERROR");
                    intent.putExtra("message", "UNKNOWN");
                    requireActivity().sendBroadcast(intent);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mLogReceiver != null) {
            requireActivity().unregisterReceiver(mLogReceiver);
        }
    }

    private void process(List<DiagnosticWrapper> text) {
        if (mIgnoreProcess) {
            return;
        }
        mIgnoreProcess = true;

        SpannableStringBuilder builder = new SpannableStringBuilder();

        for (DiagnosticWrapper diagnostic : new ArrayList<>(text)) {

            if (diagnostic.getKind() != null) {
                builder.append(diagnostic.getKind().name() + ": ", new ForegroundColorSpan(getColor(diagnostic.getKind())), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            builder.append(diagnostic.getMessage(Locale.getDefault()));
            if (diagnostic.getSource() != null) {
                builder.append(' ');
                addClickableFile(builder, diagnostic);
            }
            builder.append('\n');

        }
        mLogView.setText(builder);

        if (mRoot.canScrollVertically(-1)) {
            mRoot.scrollTo(0, mLogView.getBottom());
        }

        mIgnoreProcess = false;
    }

    @ColorInt
    private int getColor(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return 0xffcf6679;
            case WARNING:
                return Color.YELLOW;
            case NOTE:
                return Color.BLUE;
            default:
                return 0xffFFFFFF;
        }
    }

    private void addClickableFile(SpannableStringBuilder sb, final DiagnosticWrapper diagnostic) {
        if (diagnostic.getSource() == null || !diagnostic.getSource().exists()) {
            return;
        }
        ClickableSpan span = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                // MainFragment -> BottomEditorFragment -> AppLogFragment
                Fragment parent = getParentFragment();
                if (parent != null) {
                    Fragment main = parent.getParentFragment();
                    if (main instanceof MainFragment) {
                        ((MainFragment) main).openFile(diagnostic.getSource(),
                                (int) diagnostic.getLineNumber() - 1, 0);
                    }
                }
            }
        };

        String label = diagnostic.getSource().getName();
        label = label + ":" + diagnostic.getLineNumber();

        sb.append("[" + label + "]", span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
