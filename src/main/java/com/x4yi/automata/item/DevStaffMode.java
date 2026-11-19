package com.x4yi.automata.item;

import javax.annotation.Nullable;

public enum DevStaffMode implements IStaffMode {
    DUPLICATE_BOT("item.automata.automa_dev_staff.mode.duplicate_bot", "Duplicate Bot", "Click bot to clone at your pos"),
    REVIVE_BOTS("item.automata.automa_dev_staff.mode.revive_bots", "Revive Bots (5x5)", "Aim up to 20m to revive 5x5 AoE"),
    FORCE_DOWNED("item.automata.automa_dev_staff.mode.force_downed", "Force Downed", "Click bot to force downed state"),
    FORCE_TARGET("item.automata.automa_dev_staff.mode.force_target", "Force Target", "1) Click bot  2) Click target"),
    SQUAD_RALLY("item.automata.automa_dev_staff.mode.squad_rally", "Squad Rally", "Aim up to 20m to rally your bots"),
    HEAL_AND_FEED("item.automata.automa_dev_staff.mode.heal_and_feed", "Heal & Feed", "Click bot or 5x5 AoE to restore"),
    RESET_AI("item.automata.automa_dev_staff.mode.reset_ai", "Reset AI", "Click bot to clear AI locks & path");

    private final String translationKey;
    private final String displayName;
    private final String hint;

    DevStaffMode(String translationKey, String displayName, String hint) {
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
    public DevStaffMode cycle(int delta) {
        DevStaffMode[] all = values();
        int next = Math.floorMod(ordinal() + delta, all.length);
        return all[next];
    }

    @Nullable
    public static DevStaffMode fromName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (DevStaffMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return null;
    }
}
