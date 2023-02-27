package com.tyron.code.ui.legacyEditor;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.ThemeUtils;

import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.PrefixMatcher;
import com.tyron.completion.impl.CamelHumpMatcher;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementPresentation;

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.util.containers.FList;

import java.util.List;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
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

        LookupElementPresentation presentation = new LookupElementPresentation();
        LookupElement item = (LookupElement) getItem(pos);
        item.renderElement(presentation);


        int start = 0;
        int end = presentation.getItemText().length();

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(presentation.getItemText());

        int styles = 0;
        if (presentation.isItemTextBold()) {
            styles = styles | Typeface.BOLD;
        }
        if (presentation.isItemTextItalic()) {
            styles = styles | Typeface.ITALIC;
        }
        if (styles != 0) {
            sb.setSpan(new StyleSpan(styles), start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        if (presentation.isStrikeout()) {
            sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        if (presentation.isItemTextUnderlined()) {
            sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }

        start = end;
        for (LookupElementPresentation.TextFragment tailFragment :
                presentation.getTailFragments()) {
            end += tailFragment.text.length();

            sb.append(tailFragment.text);

            start = end;
        }

        PrefixMatcher prefixMatcher = item.getUserData(LookupElement.PREFIX_MATCHER_KEY);
        if (prefixMatcher instanceof CamelHumpMatcher) {
            CamelHumpMatcher camelHumpMatcher = (CamelHumpMatcher) prefixMatcher;
            FList<TextRange> textRanges =
                    camelHumpMatcher.matchingFragments(item.getLookupString());
            if (textRanges != null) {
                for (TextRange textRange : textRanges) {
                    sb.setSpan(new ForegroundColorSpan(getThemeColor(EditorColorScheme.KEYWORD)),
                            textRange.getStartOffset(),
                            textRange.getEndOffset(),
                            Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                }
            }
        }


        TextView label = view.findViewById(R.id.result_item_label);
        label.setText(sb);


        TextView description = view.findViewById(R.id.result_item_desc);

        if (presentation.getItemText() == null || presentation.getItemText().isEmpty()) {
            description.setVisibility(View.GONE);
        } else {
            description.setVisibility(View.VISIBLE);
            description.setText(presentation.getTypeText());
        }

        view.setTag(pos);
        ImageView imageView = view.findViewById(R.id.result_item_image);

        Icon icon = presentation.getIcon();
        if (icon == null) {
            imageView.setVisibility(View.GONE);
        } else {
            Drawable drawable = icon.loadDrawable(getContext());
            if (drawable != null) {
                imageView.setVisibility(View.VISIBLE);
                imageView.setImageDrawable(drawable);
            }
        }

        return view;
    }

    @Override
    public void attachValues(@NonNull EditorAutoCompletion window,
                             @NonNull List<CompletionItem> items) {
        List<CompletionItem> filteredElements = items.stream()
                .filter(it -> it instanceof LookupElement)
                .filter(it -> ((LookupElement) it).isValid())
                .collect(Collectors.toList());
        super.attachValues(window, filteredElements);
    }
}
