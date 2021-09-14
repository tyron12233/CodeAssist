package com.tyron.lint;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {

    private static final String CODE = "" +
            "import android.view.View;\n" +
            "public class Test {\n" +
            "   @Override\n" +
            "   public void onDraw(Canvas canvas) {\n" +
            "       super.onDraw(canvas);\n" +
            "   }\n" +
            "}";
    @Test
    public void test() {

    }
}