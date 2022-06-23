package javax.management;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class AttributeList extends ArrayList<Object> {
    private transient volatile boolean typeSafe;
    private transient volatile boolean tainted;
    private static final long serialVersionUID = -4077085769279709076L;

    public AttributeList() {
    }

    public AttributeList(int initialCapacity) {
        super(initialCapacity);
    }

    public AttributeList(AttributeList list) {
        super(list);
    }

    public AttributeList(List<Attribute> list) {
        if (list == null) {
            throw new IllegalArgumentException("Null parameter");
        } else {
            this.adding((Collection)list);
            super.addAll(list);
        }
    }

    public AttributeList asList() {
        this.typeSafe = true;
        if (this.tainted) {
            this.adding((Collection)this);
        }

        return this;
    }

    public void add(Attribute object) {
        super.add(object);
    }

    public void add(int index, Attribute object) {
        try {
            super.add(index, object);
        } catch (IndexOutOfBoundsException var4) {
            throw new RuntimeOperationsException(var4, "The specified index is out of range");
        }
    }

    public void set(int index, Attribute object) {
        try {
            super.set(index, object);
        } catch (IndexOutOfBoundsException var4) {
            throw new RuntimeOperationsException(var4, "The specified index is out of range");
        }
    }

    public boolean addAll(AttributeList list) {
        return super.addAll(list);
    }

    public boolean addAll(int index, AttributeList list) {
        try {
            return super.addAll(index, list);
        } catch (IndexOutOfBoundsException var4) {
            throw new RuntimeOperationsException(var4, "The specified index is out of range");
        }
    }

    public boolean add(Object element) {
        this.adding(element);
        return super.add(element);
    }

    public void add(int index, Object element) {
        this.adding(element);
        super.add(index, element);
    }

    public boolean addAll(Collection<?> c) {
        this.adding(c);
        return super.addAll(c);
    }

    public boolean addAll(int index, Collection<?> c) {
        this.adding(c);
        return super.addAll(index, c);
    }

    public Object set(int index, Object element) {
        this.adding(element);
        return super.set(index, element);
    }

    private void adding(Object x) {
        if (x != null && !(x instanceof Attribute)) {
            if (this.typeSafe) {
                throw new IllegalArgumentException("Not an Attribute: " + x);
            } else {
                this.tainted = true;
            }
        }
    }

    private void adding(Collection<?> c) {
        Iterator var2 = c.iterator();

        while(var2.hasNext()) {
            Object x = var2.next();
            this.adding(x);
        }

    }
}
