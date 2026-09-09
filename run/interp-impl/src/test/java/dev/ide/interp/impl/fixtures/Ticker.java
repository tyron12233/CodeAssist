package dev.ide.interp.impl.fixtures;

/** Implements only an interface, so its interpreted instance can cross out as a real Runnable. */
public class Ticker implements Runnable {

    public int ticks = 0;

    @Override
    public void run() {
        ticks++;
    }
}
