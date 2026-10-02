package com.airdeck.hid;

import java.util.Map;
import java.util.TreeMap;

/** User placements in normalized control-surface coordinates, independent of screen pixels. */
public final class GamepadLayoutConfig {
    public static final float MIN_SIZE = .65f;
    public static final float MAX_SIZE = 1.5f;
    private static final String VERSION = "v1";
    private final TreeMap<Integer, Placement> placements = new TreeMap<>();

    public static final class Placement {
        public final float x, y, size;
        private Placement(float x, float y, float size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    public Placement get(int id) { return placements.get(id); }

    /** Invalid IDs or non-finite values are ignored, preserving any existing placement. */
    public void put(int id, float normalizedX, float normalizedY, float size) {
        if (!validId(id) || !finite(normalizedX) || !finite(normalizedY) || !finite(size)) return;
        placements.put(id, new Placement(clamp(normalizedX, 0, 1), clamp(normalizedY, 0, 1),
                clamp(size, MIN_SIZE, MAX_SIZE)));
    }

    public void remove(int id) { placements.remove(id); }

    public GamepadLayoutConfig copy() {
        GamepadLayoutConfig result = new GamepadLayoutConfig();
        // Placement is immutable; only each config's map needs its own storage.
        result.placements.putAll(placements);
        return result;
    }

    /** Sorted control IDs make equivalent layouts produce identical saved data. */
    public String encode() {
        StringBuilder result = new StringBuilder(VERSION);
        for (Map.Entry<Integer, Placement> entry : placements.entrySet()) {
            Placement value = entry.getValue();
            result.append(';').append(entry.getKey()).append(',').append(value.x)
                    .append(',').append(value.y).append(',').append(value.size);
        }
        return result.toString();
    }

    /** Malformed entries are skipped independently; unsupported versions yield an empty layout. */
    public static GamepadLayoutConfig decode(String encoded) {
        GamepadLayoutConfig result = new GamepadLayoutConfig();
        if (encoded == null) return result;
        String[] entries = encoded.trim().split(";", -1);
        if (entries.length == 0 || !VERSION.equals(entries[0])) return result;
        for (int i = 1; i < entries.length; i++) {
            String[] fields = entries[i].split(",", -1);
            if (fields.length != 4) continue;
            try {
                result.put(Integer.parseInt(fields[0].trim()), Float.parseFloat(fields[1].trim()),
                        Float.parseFloat(fields[2].trim()), Float.parseFloat(fields[3].trim()));
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private static boolean validId(int id) { return id >= 0 && id <= 11 || id >= 20 && id <= 24; }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
