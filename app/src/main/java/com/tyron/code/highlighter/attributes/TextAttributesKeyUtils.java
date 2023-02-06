package com.tyron.code.highlighter.attributes;

import androidx.annotation.Nullable;

import com.tyron.code.util.VolatileNullableLazyValue;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey.TextAttributeKeyDefaultsProvider;
import org.jetbrains.kotlin.com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.kotlin.com.intellij.openapi.util.Comparing;
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.NullableLazyValue;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;

public class TextAttributesKeyUtils {

    private static final Logger LOG = Logger.getInstance(TextAttributesKey.class);

    private static final Map<TextAttributesKey, CodeAssistTextAttributes> textAttributesMap = new WeakHashMap<>();
    private static final NullableLazyValue<CodeAssistTextAttributesProvider> ourDefaultsProvider =
            VolatileNullableLazyValue.createValue(() -> ApplicationManager.getApplication().getService(CodeAssistTextAttributesProvider.class));
    private static final ThreadLocal<Set<String>> CALLED_RECURSIVELY = ThreadLocal.withInitial(HashSet::new);

    public static CodeAssistTextAttributes getDefaultAttributes(TextAttributesKey textAttributesKey) {
        CodeAssistTextAttributes defaultAttributes = textAttributesMap.get(textAttributesKey);
        if (defaultAttributes == null) {
            final CodeAssistTextAttributesProvider provider = ourDefaultsProvider.getValue();
            if (provider != null) {
                Set<String> called = CALLED_RECURSIVELY.get();
                assert called != null;
                String externalName = getExternalName(textAttributesKey);
                if (!(called.add(externalName))) {
                    return null;
                }

                try {
                    return ObjectUtils.notNull(provider.getDefaultAttributes(textAttributesKey), CodeAssistTextAttributes.DEFAULT);
                } finally {
                    called.remove(externalName);
                }
            }
        }
        return defaultAttributes;
    }



    public static String getExternalName(TextAttributesKey t) {
        try {
            Field myExternalName = t.getClass().getDeclaredField("myExternalName");
            myExternalName.setAccessible(true);

            Object o = myExternalName.get(t);
            if (o == null) {
                return null;
            }
            return (String) o;
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }

    private static TextAttributeKeyDefaultsProvider getDefaultsProvider(TextAttributesKey t) {
        try {
            Field ourDefaultsProvider = t.getClass().getDeclaredField("ourDefaultsProvider");
            ourDefaultsProvider.setAccessible(true);
            //noinspection ConstantConditions,unchecked
            return ((NotNullLazyValue<TextAttributeKeyDefaultsProvider>) ourDefaultsProvider.get(t)).getValue();
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }




    private static final ConcurrentMap<String, TextAttributesKey> REGISTRY;

    static {
        try {
            Field ourRegistry = TextAttributesKey.class.getDeclaredField("ourRegistry");
            ourRegistry.setAccessible(true);
            //noinspection unchecked
            REGISTRY = (ConcurrentMap<String, TextAttributesKey>) ourRegistry.get(null);
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }
    public static TextAttributesKey getFallbackAttributeKey(TextAttributesKey textAttributesKey) {
        try {
            Field myDefaultAttributes =
                    textAttributesKey.getClass().getDeclaredField("myFallbackAttributeKey");
            myDefaultAttributes.setAccessible(true);
            return (TextAttributesKey) myDefaultAttributes.get(textAttributesKey);
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }

    private static TextAttributesKey getOrCreate(String externalName,
                                                 CodeAssistTextAttributes defaultAttributes,
                                                 TextAttributesKey fallbackAttributeKey) {
        TextAttributesKey existing = REGISTRY.get(externalName);
        if (existing != null
            && (defaultAttributes == null || Comparing.equal(getDefaultAttributes(existing), defaultAttributes))
            && (fallbackAttributeKey == null || Comparing.equal(getDefaultAttributes(existing), fallbackAttributeKey))) {
            return existing;
        }
        return REGISTRY.compute(externalName, (oldName, oldKey) -> mergeKeys(oldName, oldKey, defaultAttributes, fallbackAttributeKey));
    }

    private static TextAttributesKey mergeKeys(String externalName,
                                               @Nullable TextAttributesKey oldKey,
                                               CodeAssistTextAttributes defaultAttributes,
                                               TextAttributesKey fallbackAttributeKey) {
        if (oldKey == null) return createTextAttributesKey(externalName, defaultAttributes, fallbackAttributeKey);
        // ouch. Someone's re-creating already existing key with different attributes.
        // Have to re-create the new one with correct attributes, re-insert to the map

        // but don't allow to rewrite not-null fallback key
        if (getFallbackAttributeKey(oldKey) != null && !getFallbackAttributeKey(oldKey).equals(fallbackAttributeKey)) {
            LOG.error(new IllegalStateException("TextAttributeKey(name:'" + externalName + "', fallbackAttributeKey:'" + fallbackAttributeKey + "') " +
                                                " was already registered with the other fallback attribute key: " + getFallbackAttributeKey(oldKey)));
        }

        // but don't allow to rewrite not-null default attributes
        if (getDefaultAttributes(oldKey) != null && !getDefaultAttributes(oldKey).equals(defaultAttributes)) {
            LOG.error(new IllegalStateException("TextAttributeKey(name:'" + externalName + "', defaultAttributes:'" + defaultAttributes + "') " +
                                                " was already registered with the other defaultAttributes: " + getDefaultAttributes(oldKey)));
        }

        CodeAssistTextAttributes newDefaults = ObjectUtils.chooseNotNull(defaultAttributes, getDefaultAttributes(oldKey)); // care with not calling unwanted providers
        TextAttributesKey newFallback = ObjectUtils.chooseNotNull(fallbackAttributeKey, getFallbackAttributeKey(oldKey));
        return createTextAttributesKey(externalName, null, newFallback);
    }

    private static TextAttributesKey createTextAttributesKey(String name, CodeAssistTextAttributes defaultAttributes, TextAttributesKey fallback) {
        try {
            Constructor<TextAttributesKey> declaredConstructor = TextAttributesKey.class.getDeclaredConstructor(String.class,
                    TextAttributes.class,
                    TextAttributesKey.class);
            declaredConstructor.setAccessible(true);
            TextAttributesKey textAttributesKey =
                    declaredConstructor.newInstance(name, null, fallback);
            textAttributesMap.put(textAttributesKey, defaultAttributes);
            return textAttributesKey;
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }

    public static TextAttributesKey createTextAttributesKey(String externalName, TextAttributesKey fallbackAttributeKey) {
        return getOrCreate(externalName, null, fallbackAttributeKey);
    }

    public static TextAttributesKey createTextAttributesKey(String name) {
        return TextAttributesKey.createTextAttributesKey(name);
    }

    /**
     * Registers a text attribute key with the specified identifier and default attributes.
     *
     * @param externalName      the unique identifier of the key.
     * @param defaultAttributes the default text attributes associated with the key.
     * @return the new key instance, or an existing instance if the key with the same
     * identifier was already registered.
     * @deprecated Use {@link #createTextAttributesKey(String, TextAttributesKey)} to guarantee compatibility with generic color schemes.
     */
    @Deprecated
    public static TextAttributesKey createTextAttributesKey(String externalName, CodeAssistTextAttributes defaultAttributes) {
        return getOrCreate(externalName, defaultAttributes, null);
    }

}
