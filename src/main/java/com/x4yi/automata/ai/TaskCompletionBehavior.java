package com.x4yi.automata.ai;

public enum TaskCompletionBehavior {
    FOLLOW_OWNER("follow", "Follow Owner"),
    STAY_PUT("stay", "Stay Put"),
    RETURN_AND_STAY("return", "Return & Stay");

    private final String id;
    private final String displayName;

    TaskCompletionBehavior(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static TaskCompletionBehavior fromId(String id) {
        for (TaskCompletionBehavior behavior : values()) {
            if (behavior.id.equalsIgnoreCase(id)) {
                return behavior;
            }
        }
        return FOLLOW_OWNER;
    }
}

