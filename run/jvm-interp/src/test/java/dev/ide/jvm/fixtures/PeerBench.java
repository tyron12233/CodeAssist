package dev.ide.jvm.fixtures;

import java.io.ByteArrayOutputStream;

/**
 * Many short-lived objects of an interpreted class over a real superclass: each one needs a real peer, the
 * shape a layout inflate has (every view an interpreted subclass of a framework view).
 */
public class PeerBench {

    static class Counting extends ByteArrayOutputStream {
        @Override
        public synchronized void write(int b) {
            super.write(b + 1);
        }
    }

    /** Builds [n] peered objects, writes one byte through each, and sums what they report back. */
    public static int make(int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) {
            Counting c = new Counting();
            c.write(1);
            sum += c.size() + c.toByteArray()[0];
        }
        return sum;
    }
}
