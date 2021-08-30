package com.tyron.code.ui.editor.log;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.LayoutInflater;

import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;

import com.tyron.code.R;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    
    public AppLogFragment() {
        
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = getArguments().getInt("id");
    }
  
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = new NestedScrollView(requireContext());
        mRoot.setFillViewport(true);

        mLogView = new TextView(requireContext());
        mLogView.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular));
        mLogView.setMovementMethod(new ScrollingMovementMethod());
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

    private final Pattern pattern = Pattern.compile("<\\$\\$(.+?)>(.+?)</\\$\\$(.+?)>", Pattern.DOTALL);

    private void process(String text) {
        Matcher matcher = pattern.matcher(text);
        SpannableStringBuilder builder = new SpannableStringBuilder();

        while (matcher.find()) {

            String type = matcher.group(1);
            if (type == null) {
                type = "";
            }
            int color;
            switch (type) {
                case "warning": color = 0xffFFFF00; break;
                case "error": color = 0xffFF0000; break;
                case "debug": color = 0xffEAEAEA; break;
                default: color = 0xffFFFFFF;
            }

            ForegroundColorSpan span = new ForegroundColorSpan(color);
            builder.append(matcher.group(2), span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.append("\n");
        }
        mLogView.setText(builder);
        mRoot.scrollTo(0, mLogView.getBottom());
    }
}
