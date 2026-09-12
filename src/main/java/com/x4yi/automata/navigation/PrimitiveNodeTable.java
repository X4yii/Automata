
package com.x4yi.automata.navigation;

import java.util.Arrays;

public final class PrimitiveNodeTable {

    public static final byte STATE_EMPTY = 0;
    public static final byte STATE_OPEN = 1;
    public static final byte STATE_CLOSED = 2;

    private long[] keys;
    private long[] parents;
    private float[] gScores;
    private byte[] actions;
    private byte[] states;
    private int mask;
    private int size;
    private int resizeThreshold;

    public PrimitiveNodeTable(int expectedCapacity) {
        int cap = nextPowerOfTwo(Math.max(64, expectedCapacity * 2));
        allocate(cap);
    }

    private void allocate(int capacity) {
        this.keys = new long[capacity];
        this.parents = new long[capacity];
        this.gScores = new float[capacity];
        this.actions = new byte[capacity];
        this.states = new byte[capacity];
        this.mask = capacity - 1;
        this.resizeThreshold = (capacity * 3) >>> 2;
        this.size = 0;
    }

    public void clear() {
        Arrays.fill(states, STATE_EMPTY);
        size = 0;
    }

    public int findSlot(long key) {
        int slot = mix64(key) & mask;
        while (true) {
            byte state = states[slot];
            if (state == STATE_EMPTY) {
                return -slot - 1;
            }
            if (keys[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    public int putOrUpdate(long key, long parent, float gScore, byte action, byte state) {
        if (size >= resizeThreshold) {
            rehash(keys.length << 1);
        }
        int slot = mix64(key) & mask;
        while (true) {
            byte existing = states[slot];
            if (existing == STATE_EMPTY) {
                keys[slot] = key;
                parents[slot] = parent;
                gScores[slot] = gScore;
                actions[slot] = action;
                states[slot] = state;
                size++;
                return slot;
            }
            if (keys[slot] == key) {
                parents[slot] = parent;
                gScores[slot] = gScore;
                actions[slot] = action;
                states[slot] = state;
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    public byte getState(int slot) {
        return states[slot];
    }

    public void setClosed(int slot) {
        states[slot] = STATE_CLOSED;
    }

    public float getGScore(int slot) {
        return gScores[slot];
    }

    public long getParent(int slot) {
        return parents[slot];
    }

    public byte getAction(int slot) {
        return actions[slot];
    }

    private void rehash(int newCapacity) {
        long[] oldKeys = keys;
        long[] oldParents = parents;
        float[] oldGScores = gScores;
        byte[] oldActions = actions;
        byte[] oldStates = states;

        allocate(newCapacity);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldStates[i] != STATE_EMPTY) {
                putOrUpdate(oldKeys[i], oldParents[i], oldGScores[i], oldActions[i], oldStates[i]);
            }
        }
    }

    private static int mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (z ^ (z >>> 33));
    }

    private static int nextPowerOfTwo(int v) {
        int n = Integer.highestOneBit(v - 1) << 1;
        return Math.max(16, n);
    }
}

