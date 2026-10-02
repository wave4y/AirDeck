package com.airdeck.hid;

public final class GamepadLayoutConfigTest {
    private static int assertions;
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static void placement(GamepadLayoutConfig config, int id, float x, float y, float size) {
        GamepadLayoutConfig.Placement value = config.get(id);
        check(value != null, "placement exists: " + id);
        check(Float.compare(value.x, x) == 0, "x preserved: " + id);
        check(Float.compare(value.y, y) == 0, "y preserved: " + id);
        check(Float.compare(value.size, size) == 0, "size preserved: " + id);
    }
    public static void main(String[] args) {
        GamepadLayoutConfig empty = new GamepadLayoutConfig();
        check(empty.get(0) == null, "new layout has no override");
        check("v1".equals(empty.encode()), "empty layout still has a version");
        check("v1".equals(GamepadLayoutConfig.decode(null).encode()), "null data is safe");
        String[] badData = {"", "garbage", "v2;0,0,0,1", "v1;", "v1;wat", "v1;0,0,0", "v1;0,0,0,1,extra"};
        for (String data : badData) check("v1".equals(GamepadLayoutConfig.decode(data).encode()), "bad data skipped: " + data);

        GamepadLayoutConfig config = new GamepadLayoutConfig();
        for (int id = 24; id >= 0; id--) config.put(id, id / 24f, .375f, .875f);
        for (int id = 0; id <= 24; id++) {
            if (id <= 11 || id >= 20) placement(config, id, id / 24f, .375f, .875f);
            else check(config.get(id) == null, "reserved ID rejected: " + id);
        }
        String encoded = config.encode();
        check(encoded.equals(GamepadLayoutConfig.decode(encoded).encode()), "all controls round trip exactly");
        check(encoded.indexOf(";2,") < encoded.indexOf(";10,"), "IDs sort numerically");
        GamepadLayoutConfig ascending = new GamepadLayoutConfig();
        for (int id = 0; id <= 24; id++) ascending.put(id, id / 24f, .375f, .875f);
        check(encoded.equals(ascending.encode()), "insertion order does not affect encoding");

        GamepadLayoutConfig copy = config.copy();
        copy.put(0, .8f, .9f, 1.2f);
        copy.remove(1);
        placement(config, 0, 0f, .375f, .875f);
        check(config.get(1) != null, "copy removal cannot mutate original");
        config.remove(2);
        check(copy.get(2) != null, "original removal cannot mutate copy");
        config.remove(-1);
        config.remove(999);
        check(config.get(2) == null, "remove clears chosen override");

        config.put(0, -50, 50, -10);
        placement(config, 0, 0, 1, GamepadLayoutConfig.MIN_SIZE);
        config.put(0, 1, 0, 99);
        placement(config, 0, 1, 0, GamepadLayoutConfig.MAX_SIZE);
        config.put(0, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE);
        placement(config, 0, 1, 0, GamepadLayoutConfig.MAX_SIZE);
        float[] nonFinite = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float invalid : nonFinite) {
            config.put(0, invalid, .5f, 1);
            config.put(0, .5f, invalid, 1);
            config.put(0, .5f, .5f, invalid);
            placement(config, 0, 1, 0, GamepadLayoutConfig.MAX_SIZE);
        }
        int[] invalidIds = {Integer.MIN_VALUE, -1, 12, 19, 25, Integer.MAX_VALUE};
        for (int id : invalidIds) {
            config.put(id, .5f, .5f, 1);
            check(config.get(id) == null, "unknown ID ignored: " + id);
        }

        GamepadLayoutConfig damaged = GamepadLayoutConfig.decode(
                "v1;0,.2,.3,1;1,NaN,.5,1;2,.5,Infinity,1;3,.5,.5,-Infinity;4,.5,.5,1e100;"
                + "5,hello,.5,1;6,.5,.5,;12,.5,.5,1;24,2,-2,9;20,.1,.2,.3;"
                + "0,NaN,.8,1;999999999999999,.5,.5,1;11,.8,.7,1.1");
        placement(damaged, 0, .2f, .3f, 1);
        for (int id = 1; id <= 6; id++) check(damaged.get(id) == null, "bad numeric entry ignored: " + id);
        check(damaged.get(12) == null, "unknown decoded ID ignored");
        placement(damaged, 24, 1, 0, GamepadLayoutConfig.MAX_SIZE);
        placement(damaged, 20, .1f, .2f, GamepadLayoutConfig.MIN_SIZE);
        placement(damaged, 11, .8f, .7f, 1.1f);
        placement(GamepadLayoutConfig.decode(" v1; 0 , .2 , .3 , 1 ;0,.4,.6,1.2 "), 0, .4f, .6f, 1.2f);
        System.out.println("PASS: " + assertions + " gamepad layout assertions");
    }
}
