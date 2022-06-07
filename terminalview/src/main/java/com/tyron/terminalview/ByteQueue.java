package com.tyron.terminalview;

/**
 * A multi-thread-safe produce-consumer byte array.
 * Only allows one producer and one consumer.
 */

class ByteQueue {
    public ByteQueue(int size) {
        mBuffer = new byte[size];
    }

    public int getBytesAvailable() {
        synchronized (this) {
            return mStoredBytes;
        }
    }

    public int read(byte[] buffer, int offset, int length) throws InterruptedException {
        if (length + offset > buffer.length) {
            throw new IllegalArgumentException("length + offset > buffer.length");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");

        }
        if (length == 0) {
            return 0;
        }
        synchronized (this) {
            while (mStoredBytes == 0) {
                wait();
            }
            int totalRead = 0;
            int bufferLength = mBuffer.length;
            boolean wasFull = bufferLength == mStoredBytes;
            while (length > 0 && mStoredBytes > 0) {
                int oneRun = Math.min(bufferLength - mHead, mStoredBytes);
                int bytesToCopy = Math.min(length, oneRun);
                System.arraycopy(mBuffer, mHead, buffer, offset, bytesToCopy);
                mHead += bytesToCopy;
                if (mHead >= bufferLength) {
                    mHead = 0;
                }
                mStoredBytes -= bytesToCopy;
                length -= bytesToCopy;
                offset += bytesToCopy;
                totalRead += bytesToCopy;
            }
            if (wasFull) {
                notify();
            }
            return totalRead;
        }
    }

    /**
     * Attempt to write the specified portion of the provided buffer to
     * the queue.  Returns the number of bytes actually written to the queue;
     * it is the caller's responsibility to check whether all of the data
     * was written and repeat the call to write() if necessary.
     */
    public int write(byte[] buffer, int offset, int length) throws InterruptedException {
        if (length + offset > buffer.length) {
            throw new IllegalArgumentException("length + offset > buffer.length");
        }
        if (length < 0) {
            throw new IllegalArgumentException("length < 0");

        }
        if (length == 0) {
            return 0;
        }
        synchronized (this) {
            int bufferLength = mBuffer.length;
            boolean wasEmpty = mStoredBytes == 0;
            while (bufferLength == mStoredBytes) {
                wait();
            }
            int tail = mHead + mStoredBytes;
            int oneRun;
            if (tail >= bufferLength) {
                tail = tail - bufferLength;
                oneRun = mHead - tail;
            } else {
                oneRun = bufferLength - tail;
            }
            int bytesToCopy = Math.min(oneRun, length);
            System.arraycopy(buffer, offset, mBuffer, tail, bytesToCopy);
            offset += bytesToCopy;
            mStoredBytes += bytesToCopy;
            if (wasEmpty) {
                notify();
            }
            return bytesToCopy;
        }
    }

    private final byte[] mBuffer;
    private int mHead;
    private int mStoredBytes;
}