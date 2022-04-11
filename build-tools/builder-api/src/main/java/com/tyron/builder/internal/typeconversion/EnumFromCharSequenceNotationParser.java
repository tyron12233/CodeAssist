package com.tyron.builder.internal.typeconversion;

import com.tyron.builder.api.internal.GUtil;
import com.tyron.builder.api.internal.exceptions.DiagnosticsVisitor;
import com.tyron.builder.api.internal.typeconversion.NotationConvertResult;
import com.tyron.builder.api.internal.typeconversion.NotationConverter;
import com.tyron.builder.api.internal.typeconversion.TypeConversionException;

import java.util.ArrayList;
import java.util.List;

public class EnumFromCharSequenceNotationParser<T extends Enum<T>> implements NotationConverter<CharSequence, T> {
    private final Class<? extends T> type;

    public EnumFromCharSequenceNotationParser(Class<? extends T> enumType) {
        assert enumType.isEnum() : "resultingType must be enum";
        this.type = enumType;
    }

    @Override
    public void convert(CharSequence notation, NotationConvertResult<? super T> result) throws TypeConversionException {
        try {
            result.converted(GUtil.toEnum(type, notation));
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException(e.getMessage(), e);
        }
    }

    @Override
    public void describe(DiagnosticsVisitor visitor) {
        List<String> values = new ArrayList<String>();
        final Enum[] enumConstants = type.getEnumConstants();
        for (Enum enumConstant : enumConstants) {
            values.add(enumConstant.name());
        }
        visitor.candidate(String.format("One of the following values: %s", GUtil.toString(values)));
        visitor.values(values);
    }
}
