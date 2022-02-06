package com.tyron.code.ui.editor.language;

import androidx.annotation.Keep;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * An editor color scheme that can be serialized and deserialized as json
 */
@Keep
public class CodeAssistColorScheme extends EditorColorScheme {

    @Expose
    @SerializedName("name")
    private String mName;

    @Expose
    @SerializedName("colors")
    private final Map<String, Integer> mNameToColorMap = new HashMap<>();

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

        String name = getName(type);
        if (name != null) {
            mNameToColorMap.replace(name, color);
        }
    }

    @Override
    public int getColor(int type) {
        String name = getName(type);
        if (name != null) {
            Integer integer = mNameToColorMap.get(name);
            if (integer != null) {
                return integer;
            }
        }
        return super.getColor(type);
    }

    public static final class Keys {
        private static final BiMap<Integer, String> sIdToNameMap = HashBiMap.create();

        static {
            sIdToNameMap.put(WHOLE_BACKGROUND, "wholeBackground");

            sIdToNameMap.put(PROBLEM_TYPO, "problemTypo");
            sIdToNameMap.put(PROBLEM_ERROR, "problemError");
            sIdToNameMap.put(PROBLEM_WARNING, "problemWarning");

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
