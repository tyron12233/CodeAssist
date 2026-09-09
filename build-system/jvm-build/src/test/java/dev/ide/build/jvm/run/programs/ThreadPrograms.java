package dev.ide.build.jvm.run.programs;

/**
 * Programs the run tests interpret, one nested class per entry point. They are compiled into the test's own
 * class output, which the test hands to the run as its classpath: the VM reads `.class` bytes from there and
 * interprets them, exactly as it does a user module's build output. Everything they touch is `java.lang`, so
 * the classpath needs nothing else on it.
 */
public final class ThreadPrograms {
    private ThreadPrograms() {}

    /** A worker thread that fails. `main` joins it and returns normally, as it would on a real JVM. */
    public static final class WorkerThrows {
        public static void main(String[] args) throws Exception {
            Thread worker = new Thread(() -> { throw new IllegalStateException("boom on a worker"); }, "worker");
            worker.start();
            worker.join();
            System.out.println("main finished");
        }
    }

    /** A worker that calls `System.exit`, which ends the whole program on a real JVM, not just its own thread.
     *  `main` blocks long past the run so the exit has to be what ends it. */
    public static final class WorkerExits {
        public static void main(String[] args) throws Exception {
            new Thread(() -> System.exit(7), "exiter").start();
            Thread.sleep(60_000);
        }
    }

    /** A daemon still sleeping when `main` returns, the reported crash: the run's teardown interrupts it, and
     *  the InterruptedException escapes the thread's body. */
    public static final class SleepingDaemon {
        public static void main(String[] args) {
            Thread sleeper = new Thread(() -> sleepUndeclared(60_000), "sleeper");
            sleeper.setDaemon(true);
            sleeper.start();
            System.out.println("main finished");
        }
    }

    /** `Thread.sleep` with its InterruptedException UNDECLARED, which is what Kotlin's `thread { sleep(...) }`
     *  compiles to, and what Java cannot express in a `Runnable` without the cast below. */
    static void sleepUndeclared(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            rethrow(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable t) throws T {
        throw (T) t;
    }
}
