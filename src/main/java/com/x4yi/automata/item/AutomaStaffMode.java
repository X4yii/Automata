package com.x4yi.automata.item;

public enum AutomaStaffMode implements IStaffMode {
    SELECT_ZONE("item.automata.automa_staff.mode.select_zone", "Select Zone", "LClick A / Shift+LClick B"),
    ASSIGN_SAVING_CHEST("item.automata.automa_staff.mode.assign_saving_chest", "Saving Chest", "LClick chest to set deposit"),
    ASSIGN_SOURCE_CHEST("item.automata.automa_staff.mode.assign_source_chest", "Source Chest", "LClick chest to set source"),
    WHIP("item.automata.automa_staff.mode.whip", "Whip", "LClick automaton to nudge"),
    TOGGLE_FOLLOW("item.automata.automa_staff.mode.toggle_follow", "Follow / Stay", "LClick automaton to toggle follow");

    private final String translationKey;
    private final String displayName;
    private final String hint;

    AutomaStaffMode(String translationKey, String displayName, String hint) {
        this.translationKey = translationKey;
        this.displayName = displayName;
        this.hint = hint;
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getHint() {
        return hint;
    }

    @Override
    public AutomaStaffMode cycle(int delta) {
        AutomaStaffMode[] all = values();
        int next = Math.floorMod(ordinal() + delta, all.length);
        return all[next];
    }

    public static AutomaStaffMode fromName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if ("ASSIGN_CHEST".equalsIgnoreCase(name)) {
            return ASSIGN_SAVING_CHEST;
        }
        for (AutomaStaffMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return null;
    }
}

