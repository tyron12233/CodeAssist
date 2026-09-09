package dev.ide.lang.jdt.compat;

/**
 * ART-safe shim for the two {@link java.beans.Introspector} statics the bundled IntelliJ platform actually
 * calls: {@code decapitalize(String)} and {@code flushCaches()}.
 *
 * <p>{@code java.beans.Introspector} is absent from Android's runtime (ART ships only a small slice of
 * {@code java.beans} — the {@code PropertyChange*} listeners — not the introspection API). ART eager-links
 * class references, so the moment an IntelliJ class that calls {@code Introspector.decapitalize} /
 * {@code flushCaches} executes that instruction it throws {@link NoClassDefFoundError}. In the reported case
 * this surfaced inside {@code PsiMethod.findSuperMethods()} — the call is wrapped in a catch-all that turns
 * the error into an empty result, so the editor's {@code @Override} check then wrongly reported valid
 * overrides as "does not override or implement". Four platform classes reach it:
 * {@code StringUtil}, {@code PropertyUtilBase}, {@code ExtensibleQueryFactory} ({@code decapitalize}) and
 * {@code GCUtil} ({@code flushCaches}).
 *
 * <p>A {@code java.*} type cannot be shipped under its own name on ART, so {@code IntrospectorArtPass} in
 * {@code build-logic} rewrites those {@code INVOKESTATIC java/beans/Introspector.*} call sites to the
 * identically-signed methods here. Desktop and tests keep the real JDK {@code Introspector}.
 */
public final class IntrospectorCompat {

    private IntrospectorCompat() {}

    /**
     * Byte-for-byte port of {@link java.beans.Introspector#decapitalize(String)}: lowercase the first
     * character, EXCEPT when the first two characters are both upper case (so an acronym like {@code "URL"}
     * stays {@code "URL"}). Callers (IntelliJ getter/setter + property-name logic) rely on this exact rule.
     */
    public static String decapitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }
        if (name.length() > 1
                && Character.isUpperCase(name.charAt(1))
                && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * No-op. The JDK method flushes {@code Introspector}'s internal {@code BeanInfo} cache; ART has no such
     * cache, so there is nothing to flush. Callers ({@code GCUtil}) use it only as a best-effort memory hint.
     */
    public static void flushCaches() {
        // Intentionally empty.
    }
}
