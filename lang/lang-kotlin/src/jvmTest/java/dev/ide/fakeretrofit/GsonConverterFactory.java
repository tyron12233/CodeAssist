package dev.ide.fakeretrofit;

/** Stands in for GsonConverterFactory: a Java class with a static create() factory returning itself. */
public final class GsonConverterFactory implements Factory {
    public static GsonConverterFactory create() { return new GsonConverterFactory(); }
}
