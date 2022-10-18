package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.tyron.builder.model.ProductFlavor;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * Implementation of List that create read-only ProductFlavor on the fly as they are
 * queried. The list itself is immutable.
 */
public class ImmutableFlavorList implements List<ProductFlavor> {

    @NonNull
    private final List<? extends ProductFlavor> list;

    @NonNull
    private final ReadOnlyObjectProvider immutableObjectProvider;

    ImmutableFlavorList(
            @NonNull List<? extends ProductFlavor> list,
            @NonNull ReadOnlyObjectProvider immutableObjectProvider) {
        this.list = list;
        this.immutableObjectProvider = immutableObjectProvider;
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return list.contains(o);
    }

    @NonNull
    @Override
    public Iterator<ProductFlavor> iterator() {
        final Iterator<? extends ProductFlavor> baseIterator = list.iterator();
        return new Iterator<ProductFlavor>() {
            @Override
            public boolean hasNext() {
                return baseIterator.hasNext();
            }

            @Override
            public ProductFlavor next() {
                return immutableObjectProvider.getProductFlavor(baseIterator.next());
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @NonNull
    @Override
    public Object[] toArray() {
        final int count = list.size();
        Object[] array = new Object[list.size()];

        for (int i = 0 ; i < count ; i++) {
            array[i] = immutableObjectProvider.getProductFlavor(list.get(i));
        }

        return array;
    }

    @NonNull
    @Override
    public <T> T[] toArray(@NonNull T[] array) {
        final int count = list.size();
        if (array.length < count) {
            //noinspection unchecked
            array = (T[]) Array.newInstance(array.getClass().getComponentType(), count);
        }

        for (int i = 0 ; i < count ; i++) {
            //noinspection unchecked
            array[i] = (T) immutableObjectProvider.getProductFlavor(list.get(i));
        }

        for (int i = count ; i < array.length; i++) {
            array[i] = null;
        }

        return array;
    }

    @Override
    public boolean add(ProductFlavor e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(@NonNull Collection<?> objects) {
        return list.containsAll(objects);
    }

    @Override
    public boolean addAll(@NonNull Collection<? extends ProductFlavor> es) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(int i, @NonNull Collection<? extends ProductFlavor> es) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(@NonNull Collection<?> objects) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(@NonNull Collection<?> objects) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProductFlavor get(int i) {
        ProductFlavor gpf = list.get(i);
        return immutableObjectProvider.getProductFlavor(gpf);
    }

    @Override
    public ProductFlavor set(int i, ProductFlavor e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void add(int i, ProductFlavor e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProductFlavor remove(int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(Object o) {
        if (o instanceof ReadOnlyProductFlavor) {
            return list.indexOf(((ReadOnlyProductFlavor) o).productFlavor);
        }
        return list.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        if (o instanceof ReadOnlyProductFlavor) {
            return list.lastIndexOf(((ReadOnlyProductFlavor) o).productFlavor);
        }
        return list.lastIndexOf(o);
    }

    @NonNull
    @Override
    public ListIterator<ProductFlavor> listIterator() {
        final ListIterator<? extends ProductFlavor> baseIterator = list.listIterator();
        return new ListIterator<ProductFlavor>() {
            @Override
            public boolean hasNext() {
                return baseIterator.hasNext();
            }

            @Override
            public ProductFlavor next() {
                return immutableObjectProvider.getProductFlavor(baseIterator.next());
            }

            @Override
            public boolean hasPrevious() {
                return baseIterator.hasPrevious();
            }

            @Override
            public ProductFlavor previous() {
                return immutableObjectProvider.getProductFlavor(baseIterator.previous());
            }

            @Override
            public int nextIndex() {
                return baseIterator.nextIndex();
            }

            @Override
            public int previousIndex() {
                return baseIterator.previousIndex();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void set(ProductFlavor productFlavor) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void add(ProductFlavor productFlavor) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @NonNull
    @Override
    public ListIterator<ProductFlavor> listIterator(int i) {
        final ListIterator<? extends ProductFlavor> baseIterator = list.listIterator(i);
        return new ListIterator<ProductFlavor>() {
            @Override
            public boolean hasNext() {
                return baseIterator.hasNext();
            }

            @Override
            public ProductFlavor next() {
                return immutableObjectProvider.getProductFlavor(baseIterator.next());
            }

            @Override
            public boolean hasPrevious() {
                return baseIterator.hasPrevious();
            }

            @Override
            public ProductFlavor previous() {
                return immutableObjectProvider.getProductFlavor(baseIterator.previous());
            }

            @Override
            public int nextIndex() {
                return baseIterator.nextIndex();
            }

            @Override
            public int previousIndex() {
                return baseIterator.previousIndex();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void set(ProductFlavor productFlavor) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void add(ProductFlavor productFlavor) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @NonNull
    @Override
    public List<ProductFlavor> subList(int i, int i2) {
        throw new UnsupportedOperationException();
    }
}