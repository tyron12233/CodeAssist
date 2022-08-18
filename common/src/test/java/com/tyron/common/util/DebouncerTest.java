package com.tyron.common.util;


import org.junit.Before;
import org.junit.Test;

/**
 * When a method is called repeatedly, only the last call should be performed
 */
public class DebouncerTest {

    private DebouncerStore<String> debounceExecutor;

    @Before
    public void init() {
        debounceExecutor = new DebouncerStore<>();
    }

    @Test
    public void testDebounce() {
        for (int i = 0; i < 4; i++) {
            testMethod(i);
        }
    }

    private void testMethod(int count) {
        debounceExecutor.registerOrGetDebouncer("test").debounce(300, () -> {
            assert count == 3;
        });
    }
}
