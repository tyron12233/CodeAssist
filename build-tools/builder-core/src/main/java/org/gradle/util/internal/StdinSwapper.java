package org.gradle.util.internal;

import org.gradle.api.Action;
import org.gradle.internal.Factory;

import java.io.InputStream;

public class StdinSwapper extends Swapper<InputStream> {

    public StdinSwapper() {
        super(
            new Factory<InputStream>() {
                @Override
                public InputStream create() {
                    return System.in;
                }
            },
            new Action<InputStream>() {
                @Override
                public void execute(InputStream newValue) {
                    System.setIn(newValue);
                }
            }
        );
    }
}
