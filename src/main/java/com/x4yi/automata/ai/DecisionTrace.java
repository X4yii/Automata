package com.x4yi.automata.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;

/**
 * Fixed-capacity ring buffer recording the most recent high-level AI, weapon,
 * pathfinding, and protection decisions for diagnostics (/automata diag and Telemetry tab).
 */
public final class DecisionTrace {

    public static final int DEFAULT_CAPACITY = 32;
    private static final long DEDUP_WINDOW_TICKS = 10L;

    private final String[] entries;
    private int head;
    private int size;
    private String lastCategory = "";
    private String lastDetail = "";
    private long lastTick = Long.MIN_VALUE;

    public DecisionTrace() {
        this(DEFAULT_CAPACITY);
    }

    public DecisionTrace(int capacity) {
        this.entries = new String[Math.max(4, capacity)];
    }

    public synchronized void record(long tick, String category, String detail) {
        if (category == null || detail == null) {
            return;
        }
        if (category.equals(lastCategory)
                && detail.equals(lastDetail)
                && (tick - lastTick) < DEDUP_WINDOW_TICKS) {
            return;
        }
        lastCategory = category;
        lastDetail = detail;
        lastTick = tick;

        String formatted = "[" + tick + "] " + category + ": " + detail;
        entries[head] = formatted;
        head = (head + 1) % entries.length;
        if (size < entries.length) {
            size++;
        }
    }

    public synchronized List<String> snapshotRecent(int maxLines) {
        if (size == 0 || maxLines <= 0) {
            return Collections.emptyList();
        }
        int count = Math.min(size, maxLines);
        List<String> out = new ArrayList<>(count);
        int start = (head - count + entries.length) % entries.length;
        for (int i = 0; i < count; i++) {
            out.add(entries[(start + i) % entries.length]);
        }
        return out;
    }

    public synchronized void writeToNbt(NBTTagCompound tag, String key, int maxLines) {
        NBTTagList list = new NBTTagList();
        for (String line : snapshotRecent(maxLines)) {
            list.appendTag(new NBTTagString(line));
        }
        tag.setTag(key, list);
    }

    public static List<String> readFromNbt(NBTTagCompound tag, String key) {
        if (tag == null || !tag.hasKey(key, Constants.NBT.TAG_LIST)) {
            return Collections.emptyList();
        }
        NBTTagList list = tag.getTagList(key, Constants.NBT.TAG_STRING);
        List<String> out = new ArrayList<>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            out.add(list.getStringTagAt(i));
        }
        return out;
    }

    public synchronized void clear() {
        head = 0;
        size = 0;
        lastCategory = "";
        lastDetail = "";
        lastTick = Long.MIN_VALUE;
    }
}
