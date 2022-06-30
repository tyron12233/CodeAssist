package com.tyron.code.ui.editor.scheme;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.annotation.StyleableRes;
import androidx.appcompat.widget.ThemeUtils;

import com.google.android.material.color.MaterialColors;
import com.google.common.collect.ImmutableMap;
import com.tyron.code.R;

import java.util.Map;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * An editor color scheme that is based on compiled xml files.
 */
public class CompiledEditorScheme extends EditorColorScheme {

    private static final Map<Integer, Integer> sResIdMap = ImmutableMap.<Integer, Integer>builder()
            .put(R.styleable.EditorColorScheme_keyword, KEYWORD)
            .put(R.styleable.EditorColorScheme_operator, OPERATOR)
            .put(R.styleable.EditorColorScheme_annotation, ANNOTATION)
            .put(R.styleable.EditorColorScheme_xmlAttributeName, ATTRIBUTE_NAME)
            .put(R.styleable.EditorColorScheme_xmlAttributeValue, ATTRIBUTE_VALUE)
            .put(R.styleable.EditorColorScheme_comment, COMMENT)
            .put(R.styleable.EditorColorScheme_htmlTag, HTML_TAG)
            .put(R.styleable.EditorColorScheme_identifierName, IDENTIFIER_NAME)
            .put(R.styleable.EditorColorScheme_identifierVar, IDENTIFIER_VAR)
            .put(R.styleable.EditorColorScheme_functionName, FUNCTION_NAME)
            .put(R.styleable.EditorColorScheme_literal, LITERAL)
            .put(R.styleable.EditorColorScheme_textNormal, TEXT_NORMAL)
            .put(R.styleable.EditorColorScheme_blockLineColor, BLOCK_LINE)
            .put(R.styleable.EditorColorScheme_problemError, PROBLEM_ERROR)
            .put(R.styleable.EditorColorScheme_problemWarning, PROBLEM_WARNING)
            .put(R.styleable.EditorColorScheme_problemTypo, PROBLEM_TYPO)
            .put(R.styleable.EditorColorScheme_selectedTextBackground, SELECTED_TEXT_BACKGROUND)
            .put(R.styleable.EditorColorScheme_completionPanelBackground, AUTO_COMP_PANEL_BG)
            .put(R.styleable.EditorColorScheme_completionPanelStrokeColor, AUTO_COMP_PANEL_CORNER)
            .put(R.styleable.EditorColorScheme_lineNumberBackground, LINE_NUMBER_BACKGROUND)
            .put(R.styleable.EditorColorScheme_lineNumberTextColor, LINE_NUMBER_PANEL_TEXT)
            .put(R.styleable.EditorColorScheme_lineNumberDividerColor, LINE_DIVIDER)
            .put(R.styleable.EditorColorScheme_wholeBackground, WHOLE_BACKGROUND)
            .build();

    public CompiledEditorScheme(Context context) {
        Resources.Theme theme = context.getTheme();
        TypedValue value = new TypedValue();
        theme.resolveAttribute(R.attr.editorColorScheme, value, true);
        TypedArray typedArray = context.obtainStyledAttributes(value.data, R.styleable.EditorColorScheme);
        for (Integer resId : sResIdMap.keySet()) {
            putColor(context, resId, typedArray);
        }
        typedArray.recycle();
    }

    private void putColor(Context context, @StyleableRes int res, TypedArray array) {
        if (!array.hasValue(res)) {
            return;
        }

        Integer integer = sResIdMap.get(res);
        if (integer == null) {
            return;
        }

        if (array.getType(res) == TypedValue.TYPE_ATTRIBUTE) {
            TypedValue typedValue = new TypedValue();
            array.getValue(res, typedValue);
            int color = MaterialColors.getColor(context, typedValue.data, -1);
            setColorInternal(integer, color);
            return;
        }
        setColorInternal(integer, array.getColor(res, 0));
    }

    private void setColorInternal(int id, int value) {
        mColors.put(id, value);
    }
}
