package com.tyron.psi.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.LanguageExtension;
import org.jetbrains.kotlin.com.intellij.lang.MetaLanguage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CompletionExtension<T> extends LanguageExtension<T> {
    public CompletionExtension(String epName) {
        super(epName);
    }

    @NotNull
    @Override
    protected List<T> buildExtensions(@NotNull String stringKey, @NotNull Language key) {
        return buildExtensions(getAllBaseLanguageIdsWithAny(key));
    }

    @Override
    public void invalidateCacheForExtension(String key) {
        super.invalidateCacheForExtension(key);
        // clear the entire cache because, if languages are unloaded, we won't be able to find cache keys for unloaded dialects of
        // a given language
        clearCache();

        if ("any".equals(key)) {
            for (Language language : Language.getRegisteredLanguages()) {
                super.invalidateCacheForExtension(keyToString(language));
            }
        }
    }

    @NotNull
    private Set<String> getAllBaseLanguageIdsWithAny(@NotNull Language key) {
        Set<String> allowed = new HashSet<>();
        while (key != null) {
            allowed.add(keyToString(key));
            for (MetaLanguage metaLanguage : MetaLanguage.EP_NAME.getExtensionList()) {
                if (metaLanguage.matchesLanguage(key))
                    allowed.add(metaLanguage.getID());
            }
            key = key.getBaseLanguage();
        }
        allowed.add("any");
        return allowed;
    }
}
