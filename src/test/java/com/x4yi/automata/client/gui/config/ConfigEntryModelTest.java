package com.x4yi.automata.client.gui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import net.minecraftforge.fml.client.config.ConfigGuiType;
import net.minecraftforge.fml.client.config.DummyConfigElement;
import net.minecraftforge.fml.client.config.IConfigElement;
import org.junit.jupiter.api.Test;

class ConfigEntryModelTest {

    @Test
    void wrapsForgeConfigElementsAndSupportsFilterResetAndApply() {
        DummyConfigElement boolEl = new DummyConfigElement("CothInmune", Boolean.TRUE, ConfigGuiType.BOOLEAN, "coth");
        DummyConfigElement.DummyCategoryElement root =
                new DummyConfigElement.DummyCategoryElement("General", "general", Collections.<IConfigElement>singletonList(boolEl));

        List<ConfigEntryModel> entries = ConfigEntryModel.collectElements("General", root);
        assertEquals(1, entries.size());

        ConfigEntryModel coth = entries.get(0);
        assertTrue(coth.matchesFilter("coth"));
        assertTrue(coth.matchesFilter("General"));
        assertFalse(coth.matchesFilter("zzzz_nonexistent"));

        coth.setDraftValue(Boolean.FALSE);
        assertFalse(coth.isDefaultValue());
        coth.resetToDefault();
        assertTrue(coth.isDefaultValue());
        assertEquals(Boolean.TRUE, coth.getDraftValue());
        coth.applyToElement();

        assertEquals(0.0F, GuiAutomataConfigScreen.computeMarqueeOffset(60, 80, 2.0F));
        assertTrue(GuiAutomataConfigScreen.computeMarqueeOffset(120, 80, 1.8F) > 0.0F);
    }
}
