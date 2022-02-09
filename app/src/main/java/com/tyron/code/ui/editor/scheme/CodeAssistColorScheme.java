package com.tyron.code.ui.editor.scheme;

import android.graphics.Color;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * An editor color scheme that can be serialized and deserialized as json
 */
@Keep
public class CodeAssistColorScheme extends EditorColorScheme  {

    @WorkerThread
    public static CodeAssistColorScheme fromFile(@NonNull File file) throws IOException {
        String contents = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        CodeAssistColorScheme scheme =
                new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create().fromJson(contents, CodeAssistColorScheme.class);
        if (scheme == null) {
            throw new IOException("Unable to parse scheme file.");
        }
        if (scheme.mName == null) {
            throw new IOException("Scheme does not contain a name.");
        }
        if (scheme.mColors == null) {
            throw new IOException("Scheme does not have colors.");
        }
        return scheme;
    }

    @Expose
    @SerializedName("name")
    private String mName;

    @Expose
    @SerializedName("colors")
    private Map<String, String> mNameToColorMap;

    public CodeAssistColorScheme() {
        for (Integer id : Keys.sIdToNameMap.keySet()) {
            int color = getColor(id);
            setColor(id, color);
        }
    }

    /**
     * Return the key of the id that will be serialized.
     * @param id The editor color scheme id
     * @return the mapped key
     */
    protected String getName(int id) {
        return Keys.sIdToNameMap.get(id);
    }

    @Override
    public void setColor(int type, int color) {
        super.setColor(type, color);

        if (mNameToColorMap == null) {
            mNameToColorMap = new HashMap<>();
        }

        String name = getName(type);
        if (name != null) {
            mNameToColorMap.remove(name);
            mNameToColorMap.put(name, "#" + Integer.toHexString(color));
        }
    }

    @Override
    public int getColor(int type) {
        if (mNameToColorMap == null) {
            mNameToColorMap = new HashMap<>();
        }

        String name = getName(type);
        if (name != null) {
            String color = mNameToColorMap.get(name);
            if (color != null) {
                try {
                    return Color.parseColor(color);
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
            }
        }

        return super.getColor(type);
    }

    @NonNull
    public String toString() {
        return new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .setPrettyPrinting()
                .create()
                .toJson(this);
    }

    public static final class Keys {
        private static final BiMap<Integer, String> sIdToNameMap = HashBiMap.create();

        static {
            sIdToNameMap.put(WHOLE_BACKGROUND, "wholeBackground");
            sIdToNameMap.put(AUTO_COMP_PANEL_BG, "completionPanelBackground");
            sIdToNameMap.put(AUTO_COMP_PANEL_CORNER, "completionPanelStrokeColor");
            sIdToNameMap.put(LINE_NUMBER, "lineNumber");
            sIdToNameMap.put(LINE_NUMBER_BACKGROUND, "lineNumberBackground");
            sIdToNameMap.put(LINE_NUMBER_PANEL, "lineNumberPanel");
            sIdToNameMap.put(LINE_NUMBER_PANEL_TEXT, "lineNumberPanelText");
            sIdToNameMap.put(LINE_DIVIDER, "lineDivider");
            sIdToNameMap.put(SELECTION_HANDLE, "selectionHandle");
            sIdToNameMap.put(SELECTION_INSERT, "selectionInsert");
            sIdToNameMap.put(SCROLL_BAR_TRACK, "scrollbarTrack");
            sIdToNameMap.put(SCROLL_BAR_THUMB, "scrollbarThumb");
            sIdToNameMap.put(SCROLL_BAR_THUMB_PRESSED, "scrollbarThumbPressed");

            sIdToNameMap.put(PROBLEM_TYPO, "problemTypo");
            sIdToNameMap.put(PROBLEM_ERROR, "problemError");
            sIdToNameMap.put(PROBLEM_WARNING, "problemWarning");

            sIdToNameMap.put(BLOCK_LINE, "blockLine");
            sIdToNameMap.put(BLOCK_LINE_CURRENT, "blockLineCurrent");
            sIdToNameMap.put(UNDERLINE, "underline");
            sIdToNameMap.put(CURRENT_LINE, "currentLine");

            sIdToNameMap.put(TEXT_NORMAL, "textNormal");
            sIdToNameMap.put(SELECTED_TEXT_BACKGROUND, "selectedTextBackground");
            sIdToNameMap.put(MATCHED_TEXT_BACKGROUND, "matchedTextBackground");
            sIdToNameMap.put(ATTRIBUTE_NAME, "attributeName");
            sIdToNameMap.put(ATTRIBUTE_VALUE, "attributeValue");
            sIdToNameMap.put(HTML_TAG, "htmlTag");
            sIdToNameMap.put(ANNOTATION, "annotation");
            sIdToNameMap.put(FUNCTION_NAME, "functionName");
            sIdToNameMap.put(IDENTIFIER_NAME, "identifierName");
            sIdToNameMap.put(IDENTIFIER_VAR, "identifierVar");
            sIdToNameMap.put(LITERAL, "literal");
            sIdToNameMap.put(OPERATOR, "operator");
            sIdToNameMap.put(COMMENT, "comment");
            sIdToNameMap.put(KEYWORD, "keyword");
        }
    }
}
