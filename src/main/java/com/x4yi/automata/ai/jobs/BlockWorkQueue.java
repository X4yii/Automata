package com.x4yi.automata.ai.jobs;

import java.util.function.LongPredicate;

import com.x4yi.automata.navigation.PackedPos;

public final class BlockWorkQueue {

    public static final int DEFAULT_CAPACITY = 256;

    private final long[] buffer;
    private int head;
    private int tail;
    private int size;

    public BlockWorkQueue() {
        this(DEFAULT_CAPACITY);
    }

    public BlockWorkQueue(int capacity) {
        int cap = Math.max(16, capacity);
        this.buffer = new long[cap];
    }

    public int capacity() {
        return buffer.length;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isFull() {
        return size == buffer.length;
    }

    public int remainingCapacity() {
        return buffer.length - size;
    }

    public boolean needsRefill(int lowWatermark) {
        return size < lowWatermark;
    }

    public void clear() {
        head = 0;
        tail = 0;
        size = 0;
    }

    public boolean append(long packedPos) {
        if (size >= buffer.length) {
            return false;
        }
        buffer[tail] = packedPos;
        tail = (tail + 1) % buffer.length;
        size++;
        return true;
    }

    public void pushPriority(long packedPos) {
        if (size > 0 && buffer[head] == packedPos) {
            return;
        }
        if (size >= buffer.length) {
            tail = (tail - 1 + buffer.length) % buffer.length;
            size--;
        }
        head = (head - 1 + buffer.length) % buffer.length;
        buffer[head] = packedPos;
        size++;
    }

    public long peek() {
        return size == 0 ? Long.MIN_VALUE : buffer[head];
    }

    public long poll() {
        if (size == 0) {
            return Long.MIN_VALUE;
        }
        long val = buffer[head];
        head = (head + 1) % buffer.length;
        size--;
        return val;
    }

    public long pollNextValid(LongPredicate validator) {
        while (size > 0) {
            long candidate = poll();
            if (validator.test(candidate)) {
                return candidate;
            }
        }
        return Long.MIN_VALUE;
    }

    public boolean contains(long packedPos) {
        for (int i = 0; i < size; i++) {
            if (buffer[(head + i) % buffer.length] == packedPos) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies up to {@code maxCount} queued packed positions in FIFO order without mutating the queue.
     */
    public int copyTo(long[] dest, int offset, int maxCount) {
        int s = Math.max(0, Math.min(this.size, buffer.length));
        int h = Math.floorMod(this.head, buffer.length);
        int available = Math.max(0, Math.min(s, Math.min(maxCount, dest.length - offset)));
        for (int i = 0; i < available; i++) {
            dest[offset + i] = buffer[(h + i) % buffer.length];
        }
        return available;
    }
}
