package com.x4yi.automata.ai;

public enum AutomatonJobType {
    IDLE("idle"),
    EXCAVATION("excavation"),
    MINE("mine"),
    LUMBERJACK("lumberjack"),
    FARMER("farmer"),
    DEFEND("defend"),
    CRAZY_DEFEND("crazy_defend"),
    COURIER("courier");

    private final String id;

    AutomatonJobType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static AutomatonJobType fromId(String id) {
        for (AutomatonJobType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return IDLE;
    }
}
