package com.x4yi.automata.item;

public interface IStaffMode {

    String name();

    String getTranslationKey();

    String getDisplayName();

    String getHint();

    IStaffMode cycle(int delta);
}
