package dev.ide.psi.compat;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Class-dispatched stand-ins for the fastutil interface calls inside IntelliJ's PSI tree builder.
 *
 * <p>On Android 12 / 12L (api 31/32) ART, the compiled {@code invoke-interface IntSet.contains(int)} in
 * {@code MarkerOptionalData.isCollapsed} (inlined into {@code PsiBuilderImpl$ProductionMarker.isCollapsed})
 * is dispatched to the WRONG method: {@code AbstractIntCollection.removeAll(Collection)}. That method's first
 * instruction is {@code instanceof IntCollection} on its argument, which is the marker id (an {@code int}), so
 * the interpreter dereferences the marker id as an object and the process dies with SIGSEGV at a fault address
 * equal to the id (0x6, 0x7, 0x8, ...). This is the field's largest native crash cluster, and it is confined to
 * api 31/32. The defect is in ART's interface dispatch, not in our code or IntelliJ's.
 *
 * <p>{@code FastutilInterfaceDispatchArtPass} (in {@code build-logic}) rewrites each such call site to an
 * {@code invokestatic} here. Each method checks for the concrete class IntelliJ always stores in these fields
 * and calls it through {@code invokevirtual} (vtable dispatch), so the call never goes through ART's interface
 * method table. Any other implementation falls back to the original interface call, so behaviour is identical.
 */
public final class FastutilDirectCalls {

    private FastutilDirectCalls() {}

    public static boolean intSetContains(IntSet set, int key) {
        if (set instanceof IntOpenHashSet) return ((IntOpenHashSet) set).contains(key);
        return set.contains(key);
    }

    public static boolean intSetAdd(IntSet set, int key) {
        if (set instanceof IntOpenHashSet) return ((IntOpenHashSet) set).add(key);
        return set.add(key);
    }

    public static boolean intSetRemove(IntSet set, int key) {
        if (set instanceof IntOpenHashSet) return ((IntOpenHashSet) set).remove(key);
        return set.remove(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object int2ObjectGet(Int2ObjectMap map, int key) {
        if (map instanceof Int2ObjectOpenHashMap) return ((Int2ObjectOpenHashMap) map).get(key);
        return map.get(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object int2ObjectPut(Int2ObjectMap map, int key, Object value) {
        if (map instanceof Int2ObjectOpenHashMap) return ((Int2ObjectOpenHashMap) map).put(key, value);
        return map.put(key, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object int2ObjectRemove(Int2ObjectMap map, int key) {
        if (map instanceof Int2ObjectOpenHashMap) return ((Int2ObjectOpenHashMap) map).remove(key);
        return map.remove(key);
    }
}
