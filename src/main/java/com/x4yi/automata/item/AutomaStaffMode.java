package com.x4yi.automata.item;

/**
 * Selectable operating modes for {@link ItemAutomaStaff}, cycled via Shift + Scroll.
 */
public enum AutomaStaffMode {
    SELECT_ZONE("item.automata.automa_staff.mode.select_zone", "Seleccionar Zona", "Click A -> B to set work zone"),
    ASSIGN_CHEST("item.automata.automa_staff.mode.assign_chest", "Asignar Cofre", "Right-click chest to bind"),
    WHIP("item.automata.automa_staff.mode.whip", "Azotar", "Left-click automaton to whip"),
    TOGGLE_FOLLOW("item.automata.automa_staff.mode.toggle_follow", "Seguir / Esperar", "Click automaton to toggle follow");

    private final String translationKey;
    private final String displayName;
    private final String hint;

    AutomaStaffMode(String translationKey, String displayName, String hint) {
        this.translationKey = translationKey;
        this.displayName = displayName;
        this.hint = hint;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getHint() {
        return hint;
    }

    public AutomaStaffMode cycle(int delta) {
        AutomaStaffMode[] all = values();
        int step = delta >= 0 ? 1 : -1;
        int next = (ordinal() + step + all.length) % all.length;
        return all[next];
    }

    public static AutomaStaffMode fromName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (AutomaStaffMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return null;
    }
}

