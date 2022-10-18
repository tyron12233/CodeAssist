package org.gradle.api.internal.tasks.options;

import org.gradle.internal.Cast;
import org.gradle.internal.exceptions.DiagnosticsVisitor;
import org.gradle.internal.typeconversion.NotationConverter;
import org.gradle.internal.typeconversion.NotationConverterToNotationParserAdapter;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.internal.typeconversion.EnumFromCharSequenceNotationParser;

public class OptionValueNotationParserFactory {
    public <T> NotationParser<CharSequence, T> toComposite(Class<T> targetType) throws OptionValidationException {
        if (targetType.isAssignableFrom(String.class)) {
            return Cast.uncheckedCast(new NoDescriptionValuesJustReturningParser());
        } else if (targetType.isEnum()) {
            @SuppressWarnings({"rawtypes", "unchecked"}) NotationConverter<CharSequence, T>
                    converter = new EnumFromCharSequenceNotationParser(targetType.asSubclass(Enum.class));
            return new NotationConverterToNotationParserAdapter<CharSequence, T>(converter);
        }

        throw new OptionValidationException(String.format("Don't know how to convert strings to type '%s'.", targetType.getName()));
    }

    private static class NoDescriptionValuesJustReturningParser implements NotationParser<CharSequence, String> {
        @Override
        public String parseNotation(CharSequence notation) {
            return notation.toString();
        }

        @Override
        public void describe(DiagnosticsVisitor visitor) {
            visitor.candidate("Instances of String or CharSequence.");
        }
    }
}
