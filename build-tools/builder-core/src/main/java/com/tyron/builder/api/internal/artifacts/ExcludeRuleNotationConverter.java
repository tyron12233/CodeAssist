package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.artifacts.ExcludeRule;
import com.tyron.builder.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.internal.typeconversion.MapKey;
import com.tyron.builder.internal.typeconversion.MapNotationConverter;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.typeconversion.NotationParserBuilder;

import javax.annotation.Nullable;

public class ExcludeRuleNotationConverter extends MapNotationConverter<ExcludeRule> {

    private static final NotationParser<Object, ExcludeRule> PARSER =
            NotationParserBuilder.toType(ExcludeRule.class).converter(new ExcludeRuleNotationConverter()).toComposite();

    public static NotationParser<Object, ExcludeRule> parser() {
        return PARSER;
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        visitor.candidate("Maps with 'group' and/or 'module'").example("[group: 'com.google.collections', module: 'google-collections']");
    }

    protected ExcludeRule parseMap(
        @MapKey(ExcludeRule.GROUP_KEY) @Nullable String group,
        @MapKey(ExcludeRule.MODULE_KEY) @Nullable String module
    ) {
        if (group == null && module == null) {
            throw new InvalidUserDataException("Dependency exclude rule requires 'group' and/or 'module' specified. For example: [group: 'com.google.collections']");
        }
        return new DefaultExcludeRule(group, module);
    }
}
