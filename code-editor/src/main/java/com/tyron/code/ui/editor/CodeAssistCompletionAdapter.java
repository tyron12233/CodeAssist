package com.tyron.code.ui.editor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.ThemeUtils;

import com.tyron.common.util.AndroidUtilities;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;
import io.github.rosemoe.sora2.R;

public class CodeAssistCompletionAdapter extends EditorCompletionAdapter {

    @Override
    public int getItemHeight() {
        return AndroidUtilities.dp(50);
    }

    @Override
    protected View getView(int pos, View view, ViewGroup parent, boolean isCurrentCursorPosition) {
        if (view == null) {
            view = LayoutInflater.from(getContext())
                    .inflate(R.layout.completion_result_item, parent, false);
        }
        if (isCurrentCursorPosition) {
            int color = ThemeUtils.getThemeAttrColor(getContext(), R.attr.colorControlHighlight);
            view.setBackgroundColor(color);
        } else {
            view.setBackground(null);
        }

        CompletionItem item = getItem(pos);
        TextView tv = view.findViewById(R.id.result_item_label);
        tv.setText(item.label);
        tv = view.findViewById(R.id.result_item_desc);
        tv.setText(item.desc);
        view.setTag(pos);
        ImageView iv = view.findViewById(R.id.result_item_image);
        if (item.icon == null) {
            iv.setVisibility(View.GONE);
        } else {
            iv.setVisibility(View.VISIBLE);
            iv.setImageDrawable(item.icon);
        }

        return view;
    }
}
