package com.tyron.builder.language.base;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.model.ModelMap;

/**
 * A container holding {@link LanguageSourceSet}s with a similar function
 * (production code, test code, etc.).
 */
@Incubating
public interface FunctionalSourceSet extends ModelMap<LanguageSourceSet> {
}
