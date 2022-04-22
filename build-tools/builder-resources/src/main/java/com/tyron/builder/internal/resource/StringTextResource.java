package com.tyron.builder.internal.resource;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.Describables;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.hash.PrimitiveHasher;
import com.tyron.builder.api.resources.ResourceException;

import javax.annotation.Nullable;
import java.io.File;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;

public class StringTextResource implements TextResource {
    private static final HashCode SIGNATURE = Hashes.signature(StringTextResource.class);

    private final String displayName;
    private final CharSequence contents;
    private HashCode contentHash;

    public StringTextResource(String displayName, CharSequence contents) {
        this.displayName = displayName;
        this.contents = contents;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public DisplayName getLongDisplayName() {
        return Describables.of(displayName);
    }

    @Override
    public DisplayName getShortDisplayName() {
        return getLongDisplayName();
    }

    @Override
    public boolean isContentCached() {
        return true;
    }

    @Override
    public boolean getHasEmptyContent() {
        return contents.length() == 0;
    }

    @Override
    public Reader getAsReader() {
        return new StringReader(getText());
    }

    @Override
    public String getText() {
        return contents.toString();
    }

    @Override
    public HashCode getContentHash() throws ResourceException {
        if (contentHash == null) {
            PrimitiveHasher hasher = Hashes.newPrimitiveHasher();
            hasher.putHash(SIGNATURE);
            hasher.putString(getText());
            contentHash = hasher.hash();
        }
        return contentHash;
    }

    @Override
    public File getFile() {
        return null;
    }

    @Override
    public Charset getCharset() {
        return null;
    }

    @Override
    public ResourceLocation getLocation() {
        return new StringResourceLocation(displayName);
    }

    @Override
    public boolean getExists() {
        return true;
    }

    private static class StringResourceLocation implements ResourceLocation {
        private final String displayName;

        public StringResourceLocation(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Nullable
        @Override
        public File getFile() {
            return null;
        }

        @Nullable
        @Override
        public URI getURI() {
            return null;
        }
    }
}