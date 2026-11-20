package dev.ide.jvm.fixtures;

import java.util.ListResourceBundle;

/** A resource bundle written as a class, which `ResourceBundle.getBundle` finds by name and instantiates. */
public class Texts extends ListResourceBundle {
    @Override
    protected Object[][] getContents() {
        return new Object[][] {{"greeting", "class hi"}, {"only", "root"}, {"count", 3}};
    }
}
