
package com.x4yi.automata.navigation;

import java.util.Arrays;

public final class PrimitiveMinHeap {

    private long[] keys;
    private float[] priorities;
    private int size;

    public PrimitiveMinHeap(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        this.keys = new long[cap];
        this.priorities = new float[cap];
    }

    public void clear() {
        size = 0;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void push(long key, float priority) {
        if (size == keys.length) {
            int nextCap = keys.length << 1;
            keys = Arrays.copyOf(keys, nextCap);
            priorities = Arrays.copyOf(priorities, nextCap);
        }
        int idx = size++;
        keys[idx] = key;
        priorities[idx] = priority;
        siftUp(idx);
    }

    public long peekKey() {
        return keys[0];
    }

    public long popKey() {
        long result = keys[0];
        int last = --size;
        if (last > 0) {
            keys[0] = keys[last];
            priorities[0] = priorities[last];
            siftDown(0);
        }
        return result;
    }

    private void siftUp(int idx) {
        long key = keys[idx];
        float prio = priorities[idx];
        while (idx > 0) {
            int parent = (idx - 1) >>> 1;
            if (prio >= priorities[parent]) {
                break;
            }
            keys[idx] = keys[parent];
            priorities[idx] = priorities[parent];
            idx = parent;
        }
        keys[idx] = key;
        priorities[idx] = prio;
    }

    private void siftDown(int idx) {
        long key = keys[idx];
        float prio = priorities[idx];
        int half = size >>> 1;
        while (idx < half) {
            int child = (idx << 1) + 1;
            int right = child + 1;
            if (right < size && priorities[right] < priorities[child]) {
                child = right;
            }
            if (prio <= priorities[child]) {
                break;
            }
            keys[idx] = keys[child];
            priorities[idx] = priorities[child];
            idx = child;
        }
        keys[idx] = key;
        priorities[idx] = prio;
    }
}

