package com.example.routermanager.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One {@code <Instance>} of a ZTE ajax reply: an ordered, alternating list of
 * {@code <ParaName>}/{@code <ParaValue>} elements.
 *
 * <p>The order and any duplicates are preserved, because the simulator renders instances back
 * into XML. Lookups are case-insensitive on the name and return the FIRST entry with that name.
 */
public final class AjaxInstance {

    private final List<String> names = new ArrayList<>();
    private final List<String> values = new ArrayList<>();

    public AjaxInstance() {
    }

    public static AjaxInstance of(Object... nameValuePairs) {
        AjaxInstance instance = new AjaxInstance();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            instance.add(String.valueOf(nameValuePairs[i]),
                    nameValuePairs[i + 1] == null ? "" : String.valueOf(nameValuePairs[i + 1]));
        }
        return instance;
    }

    public AjaxInstance copy() {
        AjaxInstance copy = new AjaxInstance();
        copy.names.addAll(names);
        copy.values.addAll(values);
        return copy;
    }

    /** Appends a pair. An empty or null name is kept so rendering stays faithful. */
    public AjaxInstance add(String name, String value) {
        names.add(name == null ? "" : name);
        values.add(value == null ? "" : value);
        return this;
    }

    public int size() {
        return names.size();
    }

    public String nameAt(int index) {
        return names.get(index);
    }

    public String valueAt(int index) {
        return values.get(index);
    }

    /** First value for {@code name}, or {@code ""} when absent. Never null. */
    public String get(String name) {
        int index = indexOf(name);
        return index < 0 ? "" : values.get(index);
    }

    public boolean has(String name) {
        return indexOf(name) >= 0;
    }

    /** Replaces the first value for {@code name}; appends the pair when it is missing. */
    public AjaxInstance set(String name, String value) {
        int index = indexOf(name);
        if (index < 0) {
            return add(name, value);
        }
        values.set(index, value == null ? "" : value);
        return this;
    }

    public long getLong(String name, long fallback) {
        return Numbers.parseLong(get(name), fallback);
    }

    public Long getLongOrNull(String name) {
        return Numbers.parseLongOrNull(get(name));
    }

    public Integer getIntOrNull(String name) {
        Long value = Numbers.parseLongOrNull(get(name));
        if (value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return value.intValue();
    }

    /** ZTE writes booleans as {@code 1}/{@code 0}; anything else is false. */
    public boolean getFlag(String name) {
        String raw = get(name).trim();
        return "1".equals(raw) || "true".equalsIgnoreCase(raw);
    }

    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            map.putIfAbsent(names.get(i), values.get(i));
        }
        return map;
    }

    private int indexOf(String name) {
        if (name == null) {
            return -1;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).toLowerCase(Locale.ROOT).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "AjaxInstance" + asMap();
    }
}
