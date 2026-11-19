package com.x4yi.automata.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

public final class AutomatonJobRegistry {

    private final Map<ResourceLocation, JobDescriptor> byId = new LinkedHashMap<>();
    private final List<JobDescriptor> ordered = new ArrayList<>();

    public synchronized void register(JobDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        ResourceLocation id = descriptor.getId();
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate automaton job id: " + id);
        }
        byId.put(id, descriptor);
        ordered.add(descriptor);
    }

    @Nullable
    public synchronized JobDescriptor get(ResourceLocation id) {
        return id == null ? null : byId.get(id);
    }

    public synchronized JobDescriptor getOrDefault(ResourceLocation id, ResourceLocation fallback) {
        JobDescriptor descriptor = id == null ? null : byId.get(id);
        if (descriptor != null) {
            return descriptor;
        }
        JobDescriptor fallbackDescriptor = fallback == null ? null : byId.get(fallback);
        if (fallbackDescriptor != null) {
            return fallbackDescriptor;
        }
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    @Nullable
    public synchronized JobDescriptor getByIndex(int index) {
        if (index < 0 || index >= ordered.size()) {
            return null;
        }
        return ordered.get(index);
    }

    public synchronized int indexOf(ResourceLocation id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public synchronized List<JobDescriptor> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(ordered));
    }

    public synchronized boolean contains(ResourceLocation id) {
        return id != null && byId.containsKey(id);
    }

    public synchronized int size() {
        return ordered.size();
    }

    public synchronized void clear() {
        byId.clear();
        ordered.clear();
    }
}

