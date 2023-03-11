package com.tyron.code;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.mock.MockApplication;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.util.ReadMostlyRWLock;
import org.junit.Test;

import java.util.function.Supplier;

public class ApplicationReadLockTest {

    @Test
    public void test() throws InterruptedException {
        ApplicationImpl application = new ApplicationImpl(Disposer.newDisposable(),
                Thread::currentThread);

        new Thread(() -> {
            application.runReadAction(() -> {
                System.out.println("Read action: " + Thread.currentThread());
            });
        }, "SomeThread").start();

        new Thread(() -> {
            application.runWriteAction(() -> {

            });
        }, "AnotherThread").start();

        Thread.sleep(300);
    }

    class ApplicationImpl extends MockApplication {

        private final ReadMostlyRWLock lock;

        public ApplicationImpl(@NotNull Disposable parentDisposable, Supplier<Thread> writeThreadSupplier) {
            super(parentDisposable);

            lock = new ReadMostlyRWLock(writeThreadSupplier.get());
        }

        @Override
        public <T> T runReadAction(@NotNull Computable<T> computation) {
            ReadMostlyRWLock.Reader reader = lock.startRead();
            try {
                return computation.compute();
            } finally {
                if (reader != null) {
                    lock.endRead(reader);
                }
            }
        }

        @Override
        public <T, E extends Throwable> T runReadAction(@NotNull ThrowableComputable<T, E> computation) throws E {
            ReadMostlyRWLock.Reader reader = lock.startRead();
            try {
                return computation.compute();
            } finally {
                if (reader != null) {
                    lock.endRead(reader);
                }
            }
        }

        @Override
        public void runReadAction(@NotNull Runnable action) {
            ReadMostlyRWLock.Reader reader = lock.startRead();
            try {
                action.run();
            } finally {
                if (reader != null) {
                    lock.endRead(reader);
                }
            }
        }

        @Override
        public boolean isReadAccessAllowed() {
            return lock.isReadLockedByThisThread();
        }

        @Override
        public boolean isWriteAccessAllowed() {
            return lock.isWriteThread() && lock.isWriteLocked();
        }

//        @Override
//        public void runWriteAction(@NotNull Runnable action) {
//            Class<? extends @NotNull Runnable> clazz = action.getClass();
//            startWrite(clazz);
//            try {
//                action.run();
//            } finally {
//                endWrite(clazz);
//            }
//        }
//
//        private void startWrite(@NotNull Class<?> clazz) {
////            assertWriteIntentLockAcquired();
////            assertNotInsideListener();
//
//        }
    }
}
