package com.x4yi.automata.navigation;

import java.util.Arrays;

public final class PrimitiveNodeTable {

    public static final byte STATE_EMPTY = 0;
    public static final byte STATE_OPEN = 1;
    public static final byte STATE_CLOSED = 2;

    private static final int STATE_MASK = 3;
    private static final int GEN_STEP = 4;

    private long[] keys;
    private long[] parents;
    private float[] gScores;
    private byte[] actions;
    private int[] stateAndGen;
    private short[] scaffoldsUsed;
    private int generation = GEN_STEP;
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
        this.stateAndGen = new int[capacity];
        this.scaffoldsUsed = new short[capacity];
        this.mask = capacity - 1;
        this.resizeThreshold = (capacity * 3) >>> 2;
        this.size = 0;
    }

    public void clear() {
        generation += GEN_STEP;
        if (generation == 0) {
            Arrays.fill(stateAndGen, 0);
            generation = GEN_STEP;
        }
        size = 0;
    }

    public int findSlot(long key) {
        int slot = mix64(key) & mask;
        int gen = generation;
        while (true) {
            int sg = stateAndGen[slot];
            if ((sg & ~STATE_MASK) != gen || (sg & STATE_MASK) == STATE_EMPTY) {
                return -slot - 1;
            }
            if (keys[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    public int findOrInsertSlot(long key) {
        if (size >= resizeThreshold) {
            rehash(keys.length << 1);
        }
        int slot = mix64(key) & mask;
        int gen = generation;
        while (true) {
            int sg = stateAndGen[slot];
            if ((sg & ~STATE_MASK) != gen || (sg & STATE_MASK) == STATE_EMPTY) {
                keys[slot] = key;
                stateAndGen[slot] = gen;
                return slot;
            }
            if (keys[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    public void updateSlot(int slot, long parent, float gScore, byte action, byte state, short scaffoldCount) {
        int sg = stateAndGen[slot];
        if ((sg & STATE_MASK) == STATE_EMPTY) {
            size++;
        }
        parents[slot] = parent;
        gScores[slot] = gScore;
        actions[slot] = action;
        stateAndGen[slot] = generation | (state & STATE_MASK);
        scaffoldsUsed[slot] = scaffoldCount;
    }

    public int putOrUpdate(long key, long parent, float gScore, byte action, byte state) {
        return putOrUpdate(key, parent, gScore, action, state, (short) 0);
    }

    public int putOrUpdate(long key, long parent, float gScore, byte action, byte state, short scaffoldCount) {
        int slot = findOrInsertSlot(key);
        updateSlot(slot, parent, gScore, action, state, scaffoldCount);
        return slot;
    }

    public byte getState(int slot) {
        int sg = stateAndGen[slot];
        return (sg & ~STATE_MASK) == generation ? (byte) (sg & STATE_MASK) : STATE_EMPTY;
    }

    public void setClosed(int slot) {
        stateAndGen[slot] = generation | STATE_CLOSED;
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

    public int getScaffoldsUsed(int slot) {
        return scaffoldsUsed[slot] & 0xFFFF;
    }

    private void rehash(int newCapacity) {
        long[] oldKeys = keys;
        long[] oldParents = parents;
        float[] oldGScores = gScores;
        byte[] oldActions = actions;
        int[] oldStateAndGen = stateAndGen;
        short[] oldScaffolds = scaffoldsUsed;
        int oldGen = generation;

        allocate(newCapacity);
        generation = GEN_STEP;
        for (int i = 0; i < oldKeys.length; i++) {
            int sg = oldStateAndGen[i];
            if ((sg & ~STATE_MASK) == oldGen && (sg & STATE_MASK) != STATE_EMPTY) {
                putOrUpdate(
                        oldKeys[i],
                        oldParents[i],
                        oldGScores[i],
                        oldActions[i],
                        (byte) (sg & STATE_MASK),
                        oldScaffolds[i]);
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
