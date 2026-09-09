package dev.ide.lang.jdt.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * ART-safe stand-in for the subset of {@link java.lang.invoke.VarHandle} the bundled IntelliJ-platform
 * concurrency classes use ({@code compareAndSet}/{@code getVolatile}/{@code setVolatile} over instance fields
 * and object/int arrays).
 *
 * <p>{@code VarHandle}'s access-mode methods are {@code @PolymorphicSignature}; this device's ART verifier
 * rejects the polymorphic call sites ({@code VerifyError: expected Object[], got Object}), which crashes any
 * class whose {@code <clinit>} or methods touch a {@code VarHandle} — e.g. {@code AtomicFieldUpdater} (hit via
 * {@code XmlDocumentImpl.<clinit>}) and IntelliJ's {@code ConcurrentHashMap} forks (used pervasively). The
 * build relocates those classes' {@code VarHandle} usage to this shim (see {@code VarHandleArtPass} in
 * {@code build-logic}); desktop keeps the real {@code VarHandle}.
 *
 * <p>Each op maps to the equivalent classic {@code sun.misc.Unsafe} operation — the same lock-free semantics the
 * {@code VarHandle} form and JDK-8's {@code ConcurrentHashMap} had ({@code compareAndSwapObject} ==
 * {@code VarHandle.compareAndSet}, {@code getObjectVolatile} == {@code getVolatile}, etc.). ART exposes those
 * methods; the JDK-25 BUILD {@code sun.misc.Unsafe} has dropped them, so they are bound REFLECTIVELY at runtime
 * and, when unavailable (only in JVM unit tests), a synchronized-reflection fallback preserves correctness
 * (fields are declared {@code volatile}, so reflective get/set already carry volatile semantics; the CAS + array
 * paths take a monitor to stay atomic). The fallback never runs on ART.
 */
public final class VarHandleCompat {

    /** The {@code sun.misc.Unsafe} instance, or null when the classic API is unavailable (JVM tests only). */
    private static final Object U;
    private static final MethodHandle CAS_OBJECT, CAS_INT, CAS_LONG, GET_OBJECT, PUT_OBJECT, GET_INT, PUT_INT,
        OBJECT_FIELD_OFFSET, ARRAY_BASE, ARRAY_SCALE;

    static {
        Object u = null;
        MethodHandle casO = null, casI = null, casL = null, getO = null, putO = null, getI = null, putI = null,
            ofo = null, ab = null, as = null;
        try {
            Class<?> cls = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = cls.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            u = theUnsafe.get(null);
            MethodHandles.Lookup l = MethodHandles.lookup();
            casO = l.unreflect(cls.getMethod("compareAndSwapObject", Object.class, long.class, Object.class, Object.class));
            casI = l.unreflect(cls.getMethod("compareAndSwapInt", Object.class, long.class, int.class, int.class));
            casL = l.unreflect(cls.getMethod("compareAndSwapLong", Object.class, long.class, long.class, long.class));
            getO = l.unreflect(cls.getMethod("getObjectVolatile", Object.class, long.class));
            putO = l.unreflect(cls.getMethod("putObjectVolatile", Object.class, long.class, Object.class));
            getI = l.unreflect(cls.getMethod("getIntVolatile", Object.class, long.class));
            putI = l.unreflect(cls.getMethod("putIntVolatile", Object.class, long.class, int.class));
            ofo = l.unreflect(cls.getMethod("objectFieldOffset", Field.class));
            ab = l.unreflect(cls.getMethod("arrayBaseOffset", Class.class));
            as = l.unreflect(cls.getMethod("arrayIndexScale", Class.class));
        } catch (Throwable notAvailable) {
            u = null; // JDK-25 build sun.misc.Unsafe dropped these; use the synchronized fallback.
        }
        U = u; CAS_OBJECT = casO; CAS_INT = casI; CAS_LONG = casL; GET_OBJECT = getO; PUT_OBJECT = putO;
        GET_INT = getI; PUT_INT = putI; OBJECT_FIELD_OFFSET = ofo; ARRAY_BASE = ab; ARRAY_SCALE = as;
    }

    private final Field field;   // field handles: the target field (fallback + accessible); null for array handles
    private final long offset;   // field handles: the Unsafe field offset, or -1 when Unsafe is unavailable
    private final long arrayBase; // array handles: base byte offset, or -1
    private final long arrayScale; // array handles: element byte scale, or -1

    private VarHandleCompat(Field field, long offset, long arrayBase, long arrayScale) {
        this.field = field; this.offset = offset; this.arrayBase = arrayBase; this.arrayScale = arrayScale;
    }

    // --- factories (retargeted from MethodHandles.lookup/privateLookupIn/findVarHandle/arrayElementVarHandle) ---

    /** Retarget of {@code MethodHandles.lookup()} — its result is only fed to {@link #forField} (which ignores it),
     *  so returning null avoids executing {@code MethodHandles}/{@code privateLookupIn} on ART. */
    public static MethodHandles.Lookup lookup() { return null; }

    /** Retarget of {@code MethodHandles.privateLookupIn(...)} — ignored (see {@link #lookup}). */
    public static MethodHandles.Lookup privateLookupIn(Class<?> target, MethodHandles.Lookup lookup) { return null; }

    /** Retarget of {@code Lookup.findVarHandle(owner, name, type)} (the lookup arg is ignored). */
    public static VarHandleCompat forField(MethodHandles.Lookup ignored, Class<?> owner, String name, Class<?> type) {
        Field f = findField(owner, name);
        try { f.setAccessible(true); } catch (RuntimeException ignore) { /* module/inaccessible: fallback path still works */ }
        long off = -1;
        if (U != null) {
            try { off = (long) OBJECT_FIELD_OFFSET.invoke(U, f); } catch (Throwable ignore) { off = -1; }
        }
        return new VarHandleCompat(f, off, -1, -1);
    }

    /** Retarget of {@code MethodHandles.arrayElementVarHandle(arrayClass)}. */
    public static VarHandleCompat forArray(Class<?> arrayClass) {
        long base = -1, scale = -1;
        if (U != null) {
            try { base = (int) ARRAY_BASE.invoke(U, arrayClass); scale = (int) ARRAY_SCALE.invoke(U, arrayClass); }
            catch (Throwable ignore) { base = -1; scale = -1; }
        }
        return new VarHandleCompat(null, -1, base, scale);
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> k = owner; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignore) { /* walk up */ }
        }
        throw new IllegalStateException("no field '" + name + "' in " + owner.getName());
    }

    private long arrayByteOffset(int index) { return arrayBase + (long) index * arrayScale; }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException) return (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        return new IllegalStateException(t);
    }

    // --- field access modes ---

    public boolean compareAndSet(Object owner, int expected, int update) {
        if (U != null) { try { return (boolean) CAS_INT.invoke(U, owner, offset, expected, update); } catch (Throwable t) { throw rethrow(t); } }
        synchronized (owner) {
            try { if (field.getInt(owner) == expected) { field.setInt(owner, update); return true; } return false; }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    public boolean compareAndSet(Object owner, long expected, long update) {
        if (U != null) { try { return (boolean) CAS_LONG.invoke(U, owner, offset, expected, update); } catch (Throwable t) { throw rethrow(t); } }
        synchronized (owner) {
            try { if (field.getLong(owner) == expected) { field.setLong(owner, update); return true; } return false; }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    public boolean compareAndSet(Object owner, Object expected, Object update) {
        if (U != null) { try { return (boolean) CAS_OBJECT.invoke(U, owner, offset, expected, update); } catch (Throwable t) { throw rethrow(t); } }
        synchronized (owner) {
            try { if (field.get(owner) == expected) { field.set(owner, update); return true; } return false; }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    public Object getVolatile(Object owner) {
        if (U != null) { try { return GET_OBJECT.invoke(U, owner, offset); } catch (Throwable t) { throw rethrow(t); } }
        synchronized (owner) {
            try { return field.get(owner); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    public void setVolatile(Object owner, Object value) {
        if (U != null) { try { PUT_OBJECT.invoke(U, owner, offset, value); return; } catch (Throwable t) { throw rethrow(t); } }
        synchronized (owner) {
            try { field.set(owner, value); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }

    // --- array-element access modes ---

    public boolean compareAndSet(Object[] array, int index, Object expected, Object update) {
        if (U != null) { try { return (boolean) CAS_OBJECT.invoke(U, array, arrayByteOffset(index), expected, update); } catch (Throwable t) { throw rethrow(t); } }
        synchronized (array) { if (array[index] == expected) { array[index] = update; return true; } return false; }
    }

    public Object getVolatile(Object[] array, int index) {
        if (U != null) { try { return GET_OBJECT.invoke(U, array, arrayByteOffset(index)); } catch (Throwable t) { throw rethrow(t); } }
        synchronized (array) { return array[index]; }
    }

    public void setVolatile(Object[] array, int index, Object value) {
        if (U != null) { try { PUT_OBJECT.invoke(U, array, arrayByteOffset(index), value); return; } catch (Throwable t) { throw rethrow(t); } }
        synchronized (array) { array[index] = value; }
    }

    public int getVolatile(int[] array, int index) {
        if (U != null) { try { return (int) GET_INT.invoke(U, array, arrayByteOffset(index)); } catch (Throwable t) { throw rethrow(t); } }
        synchronized (array) { return array[index]; }
    }

    public void setVolatile(int[] array, int index, int value) {
        if (U != null) { try { PUT_INT.invoke(U, array, arrayByteOffset(index), value); return; } catch (Throwable t) { throw rethrow(t); } }
        synchronized (array) { array[index] = value; }
    }
}
