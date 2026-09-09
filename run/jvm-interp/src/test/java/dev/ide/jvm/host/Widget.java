package dev.ide.jvm.host;

/** A real supertype with no no-argument constructor: its state is set only through the two-argument
 *  constructor, so an interpreted subclass must thread its {@code super(...)} arguments to it. {@link #label()}
 *  reads that state and calls the abstract {@link #render()} an interpreted subclass overrides. */
public abstract class Widget {
    private final String name;
    private final int size;

    protected Widget(String name, int size) {
        this.name = name;
        this.size = size;
    }

    public String label() {
        return name + ":" + size + ":" + render();
    }

    protected abstract String render();
}
