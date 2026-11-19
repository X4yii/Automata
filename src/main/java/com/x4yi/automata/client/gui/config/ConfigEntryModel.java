package com.x4yi.automata.client.gui.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.ConfigGuiType;
import net.minecraftforge.fml.client.config.IConfigElement;

import com.x4yi.automata.config.AutomataConfig;

public final class ConfigEntryModel {

    private final String category;
    private final IConfigElement element;
    private Object draftValue;

    public ConfigEntryModel(String category, IConfigElement element) {
        this.category = category;
        this.element = element;
        this.draftValue = readInitialValue(element);
    }

    private static Object readInitialValue(IConfigElement element) {
        if (element.isList()) {
            Object[] raw = element.getList();
            List<String> copy = new ArrayList<>();
            if (raw != null) {
                for (Object item : raw) {
                    copy.add(item != null ? item.toString() : "");
                }
            }
            return copy;
        }
        Object val = element.get();
        if (val == null) {
            val = element.getDefault();
        }
        return normalizeScalar(element.getType(), val);
    }

    private static Object normalizeScalar(ConfigGuiType type, Object val) {
        if (val == null) {
            return switch (type) {
                case BOOLEAN -> Boolean.FALSE;
                case INTEGER -> 0;
                case DOUBLE -> 0.0D;
                default -> "";
            };
        }
        return switch (type) {
            case BOOLEAN -> (val instanceof Boolean b) ? b : Boolean.parseBoolean(val.toString());
            case INTEGER -> (val instanceof Number n) ? n.intValue() : Integer.parseInt(val.toString());
            case DOUBLE -> (val instanceof Number n) ? n.doubleValue() : Double.parseDouble(val.toString());
            default -> val.toString();
        };
    }

    public String getCategory() {
        return category;
    }

    public IConfigElement getElement() {
        return element;
    }

    public String getName() {
        return element.getName();
    }

    public String getComment() {
        try {
            String c = element.getComment();
            return c != null ? c : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public ConfigGuiType getType() {
        return element.getType();
    }

    public boolean isList() {
        return element.isList();
    }

    public Object getDraftValue() {
        return draftValue;
    }

    public void setDraftValue(Object value) {
        this.draftValue = value;
    }

    public void resetToDefault() {
        if (element.isList()) {
            Object[] defaults = element.getDefaults();
            List<String> copy = new ArrayList<>();
            if (defaults != null) {
                for (Object d : defaults) {
                    copy.add(d != null ? d.toString() : "");
                }
            }
            this.draftValue = copy;
        } else {
            this.draftValue = normalizeScalar(element.getType(), element.getDefault());
        }
    }

    public boolean isDefaultValue() {
        if (element.isList()) {
            Object[] defaults = element.getDefaults();
            List<String> defList = new ArrayList<>();
            if (defaults != null) {
                for (Object d : defaults) {
                    defList.add(d != null ? d.toString() : "");
                }
            }
            return defList.equals(draftValue);
        }
        Object def = normalizeScalar(element.getType(), element.getDefault());
        return def.equals(draftValue);
    }

    @SuppressWarnings("unchecked")
    public void applyToElement() {
        if (element.isList()) {
            List<String> list = (List<String>) draftValue;
            element.set(list.toArray(new String[0]));
        } else {
            element.set(draftValue);
        }
    }

    public boolean matchesFilter(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return getName().toLowerCase(Locale.ROOT).contains(needle)
                || getComment().toLowerCase(Locale.ROOT).contains(needle)
                || category.toLowerCase(Locale.ROOT).contains(needle);
    }

    public static List<ConfigEntryModel> loadFromAutomataConfig() {
        IConfigElement root = ConfigElement.from(AutomataConfig.class);
        return collectElements("General", root);
    }

    static List<ConfigEntryModel> collectElements(String categoryName, IConfigElement root) {
        List<ConfigEntryModel> entries = new ArrayList<>();
        if (root == null || root.getChildElements() == null) {
            return entries;
        }
        for (IConfigElement child : root.getChildElements()) {
            if (child.isProperty()) {
                entries.add(new ConfigEntryModel(categoryName, child));
            } else {
                entries.addAll(collectElements(child.getName(), child));
            }
        }
        return entries;
    }
}

