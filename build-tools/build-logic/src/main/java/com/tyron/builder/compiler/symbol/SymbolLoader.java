package com.tyron.builder.compiler.symbol;

import com.google.common.base.Charsets;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.io.Files;
import com.tyron.builder.log.ILogger;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class SymbolLoader {

    private final File mSymbolFile;
    private Table<String, String, SymbolEntry> mSymbols;
    private final ILogger mLogger;

    public static class SymbolEntry {
        private final String mName;
        private final String mType;
        private final String mValue;

        public SymbolEntry(String name, String type, String value) {
            mName = name;
            mType = type;
            mValue = value;
        }

        public String getValue() {
            return mValue;
        }

        public String getName() {
            return mName;
        }

        public String getType() {
            return mType;
        }
    }

    public SymbolLoader(Table<String, String, SymbolEntry> symbols) {
        this(null, null);

        mSymbols = symbols;
    }

    public SymbolLoader(File symbolFile, ILogger logger) {
        mSymbolFile = symbolFile;
        mLogger = logger;
    }

    public void load() throws IOException {
        if (mSymbolFile == null) {
            throw new IOException("Symbol file is null. load() should not be called when" +
                                  "the symbols are injected at runtime.");
        }
        List<String> lines = Files.readLines(mSymbolFile, Charsets.UTF_8);

        mSymbols = HashBasedTable.create();

        int lineIndex = 1;
        String line = null;

        try {
            final int count = lines.size();

            for (; lineIndex <= count ; lineIndex++) {
                line = lines.get(lineIndex-1);

                // format is "<type> <class> <name> <value>"
                // don't want to split on space as value could contain spaces.
                int pos = line.indexOf(' ');
                String type = line.substring(0, pos);
                int pos2 = line.indexOf(' ', pos + 1);
                String className = line.substring(pos + 1, pos2);
                int pos3 = line.indexOf(' ', pos2 + 1);
                String name = line.substring(pos2 + 1, pos3);
                String value = line.substring(pos3 + 1);

                mSymbols.put(className, name, new SymbolEntry(name, type, value));
            }
        } catch (IndexOutOfBoundsException e) {
            if (mLogger != null) {
                String s =
                        String.format(Locale.ENGLISH, "File format error reading %s\tline %d: '%s'",
                                      mSymbolFile.getAbsolutePath(), lineIndex, line);
                mLogger.error(s);
                throw new IOException(s, e);
            }
        }
    }

    Table<String, String, SymbolEntry> getSymbols() {
        return mSymbols;
    }
}
