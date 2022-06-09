package com.tyron.builder.api.internal.catalog.parser;

import com.google.common.collect.Interner;
import com.tyron.builder.api.InvalidUserCodeException;

import javax.annotation.Nullable;

public class StrictVersionParser {
    private final Interner<String> stringInterner;

    public StrictVersionParser(Interner<String> stringInterner) {
        this.stringInterner = stringInterner;
    }

    public RichVersion parse(@Nullable String version) {
        if (version == null) {
            return RichVersion.EMPTY;
        }
        int idx = version.indexOf("!!");
        if (idx == 0) {
            throw new InvalidUserCodeException("The strict version modifier (!!) must be appended to a valid version number");
        }
        if (idx > 0) {
            String strictly = stringInterner.intern(version.substring(0, idx));
            String prefer = stringInterner.intern(version.substring(idx+2));
            return new RichVersion(null, strictly, prefer);
        }
        return new RichVersion(stringInterner.intern(version), null, null);
    }

    public static class RichVersion {
        public static final RichVersion EMPTY = new RichVersion(null, null, null);

        public final String require;
        public final String strictly;
        public final String prefer;

        private RichVersion(@Nullable String require, @Nullable String strictly, @Nullable String prefer) {
            this.require = require;
            this.strictly = strictly;
            this.prefer = prefer;
        }
    }
}
