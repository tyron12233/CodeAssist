package org.jetbrains.kotlin.com.intellij.util.containers;

import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.AbstractIntSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntArrays;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntCollection;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntIterator;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntIterators;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.util.Arrays;
import java.util.Collection;
import java.util.PrimitiveIterator;

/**
 * A simple, brute-force implementation of a set based on a backing array.
 *
 * <p>The main purpose of this
 * implementation is that of wrapping cleanly the brute-force approach to the storage of a very
 * small number of items: just put them into an array and scan linearly to find an item.
 */
public class IntArraySet extends AbstractIntSet implements java.io.Serializable, Cloneable {
    private static final long serialVersionUID = 1L;
    /**
     * The backing array (valid up to {@link #size}, excluded).
     */
    private transient int[] a;
    /**
     * The number of valid entries in {@link #a}.
     */
    private int size;

    /**
     * Creates a new array set using the given backing array. The resulting set will have as many
     * elements as the array.
     *
     * <p>It is responsibility of the caller that the elements of <code>a</code> are distinct.
     *
     * @param a the backing array.
     */
    public IntArraySet(final int[] a) {
        this.a = a;
        size = a.length;
    }

    /**
     * Creates a new empty array set.
     */
    public IntArraySet() {
        this.a = IntArrays.EMPTY_ARRAY;
    }

    /**
     * Creates a new empty array set of given initial capacity.
     *
     * @param capacity the initial capacity.
     */
    public IntArraySet(final int capacity) {
        this.a = new int[capacity];
    }

    /**
     * Creates a new array set copying the contents of a given collection.
     *
     * @param c a collection.
     */
    public IntArraySet(IntCollection c) {
        this(c.size());
        addAll(c);
    }

    /**
     * Creates a new array set copying the contents of a given set.
     *
     * @param c a collection.
     */
    public IntArraySet(final Collection<? extends Integer> c) {
        this(c.size());
        addAll(c);
    }

    /**
     * Creates a new array set using the given backing array and the given number of elements of the
     * array.
     *
     * <p>It is responsibility of the caller that the first <code>size</code> elements of
     * <code>a</code> are distinct.
     *
     * @param a    the backing array.
     * @param size the number of valid elements in <code>a</code>.
     */
    public IntArraySet(final int[] a, final int size) {
        this.a = a;
        this.size = size;
        if (size > a.length) {
            throw new IllegalArgumentException("The provided size (" +
                                               size +
                                               ") is larger than or equal to the array size (" +
                                               a.length +
                                               ")");
        }
    }

    private int findKey(final int o) {
        for (int i = size; i-- != 0; )
            if (((a[i]) == (o))) {
                return i;
            }
        return -1;
    }

    @Override
    @SuppressWarnings("unchecked")
    public IntIterator iterator() {
        PrimitiveIterator.OfInt iterator = Arrays.stream(a).iterator();
        return new IntIterator() {
            @Override
            public int nextInt() {
                return iterator.nextInt();
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }
        };
    }

    @SuppressWarnings("unchecked")
    public boolean contains(final int k) {
        return findKey(k) != -1;
    }

    public int size() {
        return size;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(final int k) {
        final int pos = findKey(k);
        if (pos == -1) {
            return false;
        }
        final int tail = size - pos - 1;
        for (int i = 0; i < tail; i++) a[pos + i] = a[pos + i + 1];
        size--;
        return true;
    }

    @Override
    public boolean add(final int k) {
        final int pos = findKey(k);
        if (pos != -1) {
            return false;
        }
        if (size == a.length) {
            final int[] b = new int[size == 0 ? 2 : size * 2];
            for (int i = size; i-- != 0; ) b[i] = a[i];
            a = b;
        }
        a[size++] = k;
        return true;
    }

    @Override
    public void clear() {
        size = 0;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns a deep copy of this set.
     *
     * <P>This method performs a deep copy of this hash set; the data stored in the
     * set, however, is not cloned. Note that this makes a difference only for object keys.
     *
     * @return a deep copy of this set.
     */

    @SuppressWarnings("unchecked")
    public IntArraySet clone() {
        IntArraySet c;
        try {
            c = (IntArraySet) super.clone();
        } catch (CloneNotSupportedException cantHappen) {
            throw new InternalError();
        }
        c.a = a.clone();
        return c;
    }

    private void writeObject(java.io.ObjectOutputStream s) throws java.io.IOException {
        s.defaultWriteObject();
        for (int i = 0; i < size; i++) s.writeInt(a[i]);
    }


    @SuppressWarnings("unchecked")
    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        a = new int[size];
        for (int i = 0; i < size; i++) a[i] = s.readInt();
    }

}