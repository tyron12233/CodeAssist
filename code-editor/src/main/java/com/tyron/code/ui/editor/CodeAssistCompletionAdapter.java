package com.tyron.code.ui.editor;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;
import io.github.rosemoe.sora2.R;

public class CodeAssistCompletionAdapter extends EditorCompletionAdapter{
    @Override
    public int getItemHeight() {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, getContext().getResources().getDisplayMetrics());
    }

    @Override
    protected View getView(int pos, View view, ViewGroup parent,
                           boolean isCurrentCursorPosition) {
        if (view == null) {
            view = LayoutInflater.from(getContext()).inflate(R.layout.completion_result_item, parent, false);
        }
        CompletionItem item = getItem(pos);
        TextView tv = view.findViewById(R.id.result_item_label);
        tv.setText(item.label);
        tv = view.findViewById(R.id.result_item_desc);
        tv.setText(item.desc);
        view.setTag(pos);
        if (isCurrentCursorPosition) {
            view.setBackgroundColor(0xffdddddd);
        } else {
            view.setBackgroundColor(0x00ffffff);
        }
        ImageView iv = view.findViewById(R.id.result_item_image);
        iv.setImageDrawable(item.icon);
        return view;
    }
}
