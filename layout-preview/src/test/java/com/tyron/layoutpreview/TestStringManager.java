package com.tyron.layoutpreview;

import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.StringManager;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;

import org.junit.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TestStringManager {

    private final Map<String, Value> DEFAULT = new HashMap<String, Value>() {{
       put("app_name", new Primitive("TEST_APP"));
    }};

    private final Map<String, Value> CH = new HashMap<String, Value>() {{
        put("app_name", new Primitive("CH_TEST_APP"));
    }};

    private final Map<String, Map<String, Value>> VALUES = new HashMap<String, Map<String, Value>>() {{
       put(null, DEFAULT);
       put(Locale.CHINESE.toLanguageTag(), CH);
    }};

    private final StringManager manager = new StringManager() {
        @Override
        public Map<String, Value> getStrings(@Nullable String tag) {
            return VALUES.get(tag);
        }
    };

    @Test
    public void test() {
        Value value = manager.get("app_name", Locale.ENGLISH);
        assertThat(value).isNotNull();
        assertThat(value.getAsString())
                .isEqualTo("TEST_APP");
    }
}
