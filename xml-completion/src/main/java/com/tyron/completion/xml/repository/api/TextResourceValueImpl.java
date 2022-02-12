package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.util.Objects;

/** A {@link ResourceValue} intended for text nodes where we need access to the raw XML text. */
public class TextResourceValueImpl extends ResourceValueImpl implements TextResourceValue {
    @Nullable private String rawXmlValue;

    public TextResourceValueImpl(
            @NonNull ResourceNamespace namespace,
            @NonNull String name,
            @Nullable String textValue,
            @Nullable String rawXmlValue,
            @Nullable String libraryName) {
        super(namespace, ResourceType.STRING, name, textValue, libraryName);
        this.rawXmlValue = rawXmlValue;
    }

    public TextResourceValueImpl(
            @NonNull ResourceReference reference,
            @Nullable String textValue,
            @Nullable String rawXmlValue,
            @Nullable String libraryName) {
        super(reference, textValue, libraryName);
        this.rawXmlValue = rawXmlValue;
        assert reference.getResourceType() == ResourceType.STRING;
    }

    @Override
    @Nullable
    public String getRawXmlValue() {
        if (rawXmlValue != null) {
            return rawXmlValue;
        }
        return super.getValue();
    }

    /**
     * Sets the raw XML text.
     *
     * @param value the text to set
     *
     * @see #getRawXmlValue()
     */
    public void setRawXmlValue(@Nullable String value) {
        rawXmlValue = value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), rawXmlValue);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!super.equals(obj)) return false;
        TextResourceValueImpl other = (TextResourceValueImpl) obj;
        return Objects.equals(rawXmlValue, other.rawXmlValue);
    }
}
