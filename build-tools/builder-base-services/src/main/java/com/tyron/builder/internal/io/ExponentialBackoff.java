package com.tyron.builder.internal.io;

import com.tyron.builder.internal.time.CountdownTimer;
import com.tyron.builder.internal.time.Time;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class ExponentialBackoff<S extends ExponentialBackoff.Signal> {

    private static final int CAP_FACTOR = 100;
    private static final long SLOT_TIME = 25;

    private final Random random = new Random();
    private final S signal;

    private final long slotTime;
    private final int timeoutMs;
    private CountdownTimer timer;

    public static ExponentialBackoff<Signal> of(int amount, TimeUnit unit) {
        return of(amount, unit, Signal.SLEEP);
    }

    public static <T extends Signal> ExponentialBackoff<T> of(int amount, TimeUnit unit, T signal) {
        return new ExponentialBackoff<T>((int) TimeUnit.MILLISECONDS.convert(amount, unit), signal, SLOT_TIME);
    }

    public static ExponentialBackoff<Signal> of(int amount, TimeUnit unit, int slotTime, TimeUnit slotTimeUnit) {
        return new ExponentialBackoff<Signal>((int) TimeUnit.MILLISECONDS.convert(amount, unit), Signal.SLEEP, TimeUnit.MILLISECONDS.convert(slotTime, slotTimeUnit));
    }

    private ExponentialBackoff(int timeoutMs, S signal, long slotTime) {
        this.timeoutMs = timeoutMs;
        this.signal = signal;
        this.slotTime = slotTime;
        restartTimer();
    }

    public void restartTimer() {
        timer = Time.startCountdownTimer(timeoutMs);
    }

    /**
     * Retries the given query until it returns a 'sucessful' result.
     *
     * @param query which returns non-null value when successful.
     * @param <T> the result type.
     * @return the last value returned by the query.
     * @throws IOException thrown by the query.
     * @throws InterruptedException if interrupted while waiting.
     */
    public <T> T retryUntil(IOQuery<T> query) throws IOException, InterruptedException {
        int iteration = 0;
        IOQuery.Result<T> result;
        while (!(result = query.run()).isSuccessful()) {
            if (timer.hasExpired()) {
                break;
            }
            boolean signaled = signal.await(backoffPeriodFor(++iteration));
            if (signaled) {
                iteration = 0;
            }
        }
        return result.getValue();
    }

    long backoffPeriodFor(int iteration) {
        return random.nextInt(Math.min(iteration, CAP_FACTOR)) * slotTime;
    }

    public CountdownTimer getTimer() {
        return timer;
    }

    public S getSignal() {
        return signal;
    }

    public interface Signal {
        Signal SLEEP = new Signal() {
            @Override
            public boolean await(long period) throws InterruptedException {
                Thread.sleep(period);
                return false;
            }
        };

        boolean await(long period) throws InterruptedException;
    }
}