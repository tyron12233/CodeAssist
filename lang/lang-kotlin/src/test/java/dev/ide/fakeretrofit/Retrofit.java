package dev.ide.fakeretrofit;

/**
 * A faithful stand-in for retrofit2.Retrofit: a Java class with a nested static self-returning Builder whose
 * build() returns the outer type, and a create(Class) member — the exact shape of the reported case.
 */
public class Retrofit {
    public <T> T create(Class<T> service) { return null; }

    public static final class Builder {
        public Builder baseUrl(String url) { return this; }
        public Builder addConverterFactory(Factory factory) { return this; }
        public Retrofit build() { return new Retrofit(); }
    }
}
