package com.tyron.code.ui.editor.log;

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

import com.tyron.code.R;
import com.tyron.code.model.DiagnosticWrapper;
import com.tyron.code.ui.main.MainFragment;

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
    
    public AppLogFragment() {
        
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = requireArguments().getInt("id");
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

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LogViewModel model = new ViewModelProvider(requireActivity())
                .get(LogViewModel.class);
        model.getLogs(id).observe(getViewLifecycleOwner(), this::process);
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
        mRoot.scrollTo(0, mLogView.getBottom());

        mIgnoreProcess = false;
    }

    @ColorInt
    private int getColor(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR: return 0xffcf6679;
            case WARNING: return 0xffFFFF00;
            default: return 0xffFFFFFF;
        }
    }

    private void addClickableFile(SpannableStringBuilder sb, final DiagnosticWrapper diagnostic) {
        ClickableSpan span = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                // MainFragment -> BottomEditorFragment -> AppLogFragment
                Fragment parent = getParentFragment();
                if (parent != null) {
                    Fragment main = parent.getParentFragment();
                    if (main instanceof MainFragment) {
                        ((MainFragment) main).openFile(diagnostic.getSource(),
                                (int) diagnostic.getLineNumber() - 1, (int) diagnostic.getColumnNumber() - 1);
                    }
                }
            }
        };

        String label = diagnostic.getSource().getName();
        label = label + ":" + diagnostic.getLineNumber();

        sb.append("[" + label + "]", span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
