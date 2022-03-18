package com.tyron.code.ui.editor.log.adapter;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.code.R;

import javax.tools.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder>{

    public interface OnClickListener {
        void onClick(DiagnosticWrapper diagnostic);
    }

    private final List<DiagnosticWrapper> mData = new ArrayList<>();
    private OnClickListener mListener;

    public LogAdapter() {

    }

    public void setListener(OnClickListener listener) {
        mListener = listener;
    }

    public void submitList(List<DiagnosticWrapper> newData) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return mData.size();
            }

            @Override
            public int getNewListSize() {
                return newData.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return mData.get(oldItemPosition).equals(newData.get(newItemPosition));
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return mData.get(oldItemPosition).equals(newData.get(newItemPosition));
            }
        });
        mData.clear();
        mData.addAll(newData);
        try {
            result.dispatchUpdatesTo(this);
        } catch (IndexOutOfBoundsException e) {
            notifyDataSetChanged();
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout layout = new FrameLayout(parent.getContext());
        layout.setLayoutParams(new RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT));
        return new ViewHolder(layout);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(mData.get(position));
    }

    @Override
    public int getItemCount() {
        return mData.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        public TextView textView;

        public ViewHolder(FrameLayout layout) {
            super(layout);

            textView = new TextView(layout.getContext());
            textView.setTypeface(ResourcesCompat.getFont(layout.getContext(), R.font.jetbrains_mono_regular));
            textView.setMovementMethod(LinkMovementMethod.getInstance());
            layout.addView(textView);
        }

        public void bind(DiagnosticWrapper diagnostic) {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            if (diagnostic.getKind() != null) {
                builder.append(diagnostic.getKind().name() + ": ",
                        new ForegroundColorSpan(getColor(diagnostic.getKind())),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                builder.append(diagnostic.getMessage(Locale.getDefault()),
                        new ForegroundColorSpan(getColor(diagnostic.getKind())),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                builder.append(diagnostic.getMessage(Locale.getDefault()));
            }
            if (diagnostic.getSource() != null) {
                builder.append(' ');
                addClickableFile(builder, diagnostic);
            }
            textView.setText(builder);
        }
    }

    @ColorInt
    private int getColor(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return 0xffcf6679;
            case MANDATORY_WARNING:
            case WARNING:
                return Color.YELLOW;
            case NOTE:
                return Color.CYAN;
            default:
                return 0xffFFFFFF;
        }
    }

    private void addClickableFile(SpannableStringBuilder sb, final DiagnosticWrapper diagnostic) {
        if (diagnostic.getSource() == null || !diagnostic.getSource().exists()) {
            return;
        }
        if (diagnostic.getOnClickListener() != null) {
            ClickableSpan span = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    diagnostic.getOnClickListener().onClick(widget);
                }
            };
            sb.append("[" + diagnostic.getExtra() + "]", span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        ClickableSpan span = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                if (mListener != null) {
                    mListener.onClick(diagnostic);
                }
            }
        };

        String label = diagnostic.getSource().getName();
        label = label + ":" + diagnostic.getLineNumber();

        sb.append("[" + label + "]", span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
