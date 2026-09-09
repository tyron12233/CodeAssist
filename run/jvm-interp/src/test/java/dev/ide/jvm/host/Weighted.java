package dev.ide.jvm.host;

/** A real interface whose method a real ABSTRACT superclass leaves unimplemented, so the implementation is
 *  the interpreted subclass's to supply. Mirrors {@code androidx.compose.ui.text.font.Font#getWeight()},
 *  which {@code AndroidFont} declares nothing for. */
public interface Weighted {
    int weight();
}
