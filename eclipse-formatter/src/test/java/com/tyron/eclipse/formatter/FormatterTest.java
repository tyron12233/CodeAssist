package com.tyron.eclipse.formatter;

import org.junit.Before;
import org.junit.Test;

public class FormatterTest {

    @Test
    public void testFormatInvalidCode() {
        String source = "pub static void main() { }";
        String formatted = Formatter.format(source, 0, source.length());
        System.out.println(formatted);
    }
}
