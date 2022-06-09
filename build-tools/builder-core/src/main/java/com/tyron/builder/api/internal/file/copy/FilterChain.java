package com.tyron.builder.api.internal.file.copy;

import groovy.lang.Closure;
import groovy.lang.MissingPropertyException;
import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;
import org.apache.commons.io.input.ReaderInputStream;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.util.internal.ConfigureUtil;

import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

public class FilterChain implements Transformer<InputStream, InputStream> {
    private final ChainingTransformer<Reader> transformers = new ChainingTransformer<Reader>(Reader.class);
    private final String charset;

    public FilterChain() {
        this(Charset.defaultCharset().name());
    }

    public FilterChain(String charset) {
        this.charset = charset;
    }

    /**
     * Transforms the given Reader. The original Reader will be closed by the returned Reader.
     */
    public Reader transform(Reader original) {
        return transformers.transform(original);
    }

    /**
     * Transforms the given InputStream. The original InputStream will be closed by the returned InputStream.
     */
    @Override
    public InputStream transform(InputStream original) {
        try {
            return new ReaderInputStream(transform(new InputStreamReader(original, charset)), charset);
        } catch (UnsupportedEncodingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean hasFilters() {
        return transformers.hasTransformers();
    }

    public void add(Class<? extends FilterReader> filterType) {
        add(filterType, null);
    }

    public void add(final Class<? extends FilterReader> filterType, final Map<String, ?> properties) {
        transformers.add(new Transformer<Reader, Reader>() {
            @Override
            public Reader transform(Reader original) {
                try {
                    Constructor<? extends FilterReader> constructor = filterType.getConstructor(Reader.class);
                    FilterReader result = constructor.newInstance(original);

                    if (properties != null) {
                        ConfigureUtil.configureByMap(properties, result);
                    }
                    return result;
                } catch (Throwable th) {
                    throw new InvalidUserDataException("Error - Invalid filter specification for " + filterType.getName(), th);
                }
            }
        });
    }

    public void add(final Transformer<String, String> transformer) {
        transformers.add(new Transformer<Reader, Reader>() {
            @Override
            public Reader transform(Reader reader) {
                return new LineFilter(reader, transformer);
            }
        });
    }

    public void add(final Closure closure) {
        add(new ClosureBackedTransformer(closure));
    }

    public void expand(final Map<String, ?> properties, final Provider<Boolean> escapeBackslash) {
        transformers.add(new Transformer<Reader, Reader>() {
            @Override
            public Reader transform(Reader original) {
                try {
                    Template template;
                    try {
                        SimpleTemplateEngine engine = new SimpleTemplateEngine();
                        engine.setEscapeBackslash(escapeBackslash.get());
                        template = engine.createTemplate(original);
                    } finally {
                        original.close();
                    }
                    StringWriter writer = new StringWriter();
                    // SimpleTemplateEngine expects to be able to mutate the map internally.
                    template.make(new LinkedHashMap<>(properties)).writeTo(writer);
                    return new StringReader(writer.toString());
                } catch (MissingPropertyException e) {
                    throw new BuildException(String.format("Missing property (%s) for Groovy template expansion. Defined keys %s.", e.getProperty(), properties.keySet()), e);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }
}
