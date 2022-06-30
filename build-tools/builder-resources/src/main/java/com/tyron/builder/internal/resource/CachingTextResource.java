package com.tyron.builder.internal.resource;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.resources.MissingResourceException;
import com.tyron.builder.api.resources.ResourceException;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.hash.Hashes;
import com.tyron.builder.internal.hash.PrimitiveHasher;

import java.io.File;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;

public class CachingTextResource implements TextResource {
    private static final HashCode SIGNATURE = Hashes.signature(CachingTextResource.class);
    private final TextResource resource;
    private String content;
    private HashCode contentHash;

    public CachingTextResource(TextResource resource) {
        this.resource = resource;
    }

    @Override
    public String getDisplayName() {
        return resource.getDisplayName();
    }

    @Override
    public DisplayName getLongDisplayName() {
        return resource.getLongDisplayName();
    }

    @Override
    public DisplayName getShortDisplayName() {
        return resource.getShortDisplayName();
    }

    @Override
    public ResourceLocation getLocation() {
        return resource.getLocation();
    }

    @Override
    public File getFile() {
        return resource.getFile();
    }

    @Override
    public Charset getCharset() {
        return resource.getCharset();
    }

    @Override
    public boolean isContentCached() {
        return true;
    }

    @Override
    public boolean getExists() {
        try {
            maybeFetch();
        } catch (MissingResourceException e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean getHasEmptyContent() {
        maybeFetch();
        return content.length() == 0;
    }

    @Override
    public String getText() {
        maybeFetch();
        return content;
    }

    @Override
    public HashCode getContentHash() throws ResourceException {
        maybeFetch();
        return contentHash;
    }

    @Override
    public Reader getAsReader() {
        maybeFetch();
        return new StringReader(content);
    }

    private void maybeFetch() {
        if (content == null) {
            content = resource.getText();
            PrimitiveHasher hasher = Hashes.newPrimitiveHasher();
            hasher.putHash(SIGNATURE);
            hasher.putString(content);
            contentHash = hasher.hash();
        }
    }
}
