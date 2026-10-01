import com.airdeck.hid.HidReports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Run with tests/run-tests.ps1; no Android, JUnit or network dependency. */
public final class HidReportsTest {
    private static int assertions;

    public static void main(String[] args) {
        keyboardHasSixKeyRolloverAndIndependentModifiers();
        asciiMappingCoversUsKeyboardAndRejectsUnicode();
        mouseIsSignedRelativeAndBootFormatIsSeparate();
        gamepadPreservesButtonsHatAndIndependentAxes();
        descriptorMatchesActualWireReports();
        System.out.println("PASS: " + assertions + " protocol assertions");
    }

    private static void keyboardHasSixKeyRolloverAndIndependentModifiers() {
        eq("released keyboard", new int[8], HidReports.keyboard(0, Collections.emptySet()));
        eq("chord with deduplication and right modifier", new int[]{0x83,0,4,5,0,0,0,0},
                HidReports.keyboard(3, Arrays.asList(4, 5, 4, 0xe7)));
        eq("all six keys survive", new int[]{4,0,4,5,6,7,8,9},
                HidReports.keyboard(4, Arrays.asList(4,5,6,7,8,9)));
        eq("rollover error fills all slots", new int[]{1,0,1,1,1,1,1,1},
                HidReports.keyboard(1, Arrays.asList(4,5,6,7,8,9,10)));
        eq("invalid usages not sent", new int[]{0,0,4,0,0,0,0,0},
                HidReports.keyboard(0, Arrays.asList(-1,0,1,2,3,4,0x66,0xffff,null)));
        byte[] held = HidReports.keyboard(0, Arrays.asList(4));
        byte[] released = HidReports.keyboard(0, Collections.emptySet());
        check(held[2] == 4 && released[2] == 0, "reports do not share mutable storage");
    }

    private static void asciiMappingCoversUsKeyboardAndRejectsUnicode() {
        for (char c = 32; c <= 126; c++) check(HidReports.forCharacter(c) != null, "printable US ASCII " + c);
        for (char c = 'a'; c <= 'z'; c++) {
            HidReports.KeyStroke lower = HidReports.forCharacter(c), upper = HidReports.forCharacter(Character.toUpperCase(c));
            check(lower.usage == 4 + c - 'a' && lower.modifiers == 0, "lowercase usage " + c);
            check(upper.usage == lower.usage && upper.modifiers == 2, "uppercase uses Shift " + c);
        }
        String plain = "1234567890-=[]\\;'`,./";
        String shift = "!@#$%^&*()_+{}|:\"~<>?";
        for (int index = 0; index < plain.length(); index++) {
            HidReports.KeyStroke a = HidReports.forCharacter(plain.charAt(index));
            HidReports.KeyStroke b = HidReports.forCharacter(shift.charAt(index));
            check(a.usage == b.usage && a.modifiers == 0 && b.modifiers == 2, "shift pair " + plain.charAt(index));
        }
        check(HidReports.forCharacter('中') == null, "CJK cannot be sent as HID text");
        check(HidReports.forCharacter(0x1f600) == null, "emoji cannot be sent as HID text");
        check(HidReports.forCharacter(0) == null && HidReports.forCharacter(127) == null, "unsupported controls rejected");
        check(HidReports.forCharacter('\n').usage == 0x28, "newline is Enter");
        check(HidReports.forCharacter('\t').usage == 0x2b, "tab is Tab");
        check(HidReports.forCharacter('\b').usage == 0x2a, "backspace mapping");
    }

    private static void mouseIsSignedRelativeAndBootFormatIsSeparate() {
        eq("mouse extremes clamp without wrapping", new int[]{31,129,127,129}, HidReports.mouse(255,-500,500,-500));
        eq("mouse button chord", new int[]{5,255,1,127}, HidReports.mouse(5,-1,1,127));
        eq("boot mouse has only three buttons and no wheel", new int[]{7,129,127}, HidReports.bootMouse(255,-128,128));
        eq("neutral mouse", new int[4], HidReports.mouse(0,0,0,0));
    }

    private static void gamepadPreservesButtonsHatAndIndependentAxes() {
        eq("neutral pad does not press D-pad up", new int[]{0,0,8,0,0,0,0,0,0}, HidReports.gamepad(0,8,0,0,0,0,0,0));
        for (int button = 0; button < 16; button++) {
            byte[] report = HidReports.gamepad(1 << button,8,0,0,0,0,0,0);
            check(((report[0] & 255) | (report[1] & 255) << 8) == (1 << button), "button " + button + " is independent");
        }
        for (int hat = 0; hat < 8; hat++) check(HidReports.gamepad(0,hat,0,0,0,0,0,0)[2] == hat, "hat direction " + hat);
        eq("all controls and clipping", new int[]{255,255,8,129,127,255,1,0,255},
                HidReports.gamepad(0xffff,-1,-999,999,-1,1,-255,999));
        eq("both triggers independent", new int[]{1,128,7,1,2,3,4,128,254},
                HidReports.gamepad(0x8001,7,1,2,3,4,128,254));
    }

    /** Small independent HID descriptor reader: checks bit counts AND field semantics. */
    private static void descriptorMatchesActualWireReports() {
        byte[] descriptor = HidReports.descriptor();
        Descriptor parsed = new Descriptor(descriptor);
        check(parsed.topLevelCollections == 3 && parsed.depth == 0, "three balanced application collections");
        check(parsed.bits(1,8) == 64, "keyboard descriptor is 8 input bytes");
        check(parsed.bits(2,8) == 32, "mouse descriptor is 4 input bytes");
        check(parsed.bits(3,8) == 72, "gamepad descriptor is 9 input bytes");
        check(parsed.bits(1,9) == 8, "keyboard LED output is one byte");
        check(parsed.bits(2,9) == 0 && parsed.bits(3,9) == 0, "no undeclared mouse/gamepad outputs");
        check(parsed.bits(1,11) + parsed.bits(2,11) + parsed.bits(3,11) == 0, "no feature reports");
        Field mouseAxes = parsed.find(2,8,1,0x30);
        check(mouseAxes.min == -127 && mouseAxes.max == 127 && mouseAxes.size == 8 && mouseAxes.count == 3,
                "mouse axes have signed 8-bit range");
        check((mouseAxes.flags & 4) != 0, "mouse movement is relative");
        Field hat = parsed.find(3,8,1,0x39);
        check(hat.min == 0 && hat.max == 7 && hat.size == 4 && (hat.flags & 0x40) != 0,
                "hat declares eight directions and null state");
        Field stick = parsed.find(3,8,1,0x30);
        check(stick.min == -127 && stick.max == 127 && stick.size == 8 && stick.count == 4 && (stick.flags & 4) == 0,
                "gamepad sticks are four absolute signed axes");
        check(stick.unit == 0 && stick.physicalMax == 0, "hat angular unit does not leak into sticks");
        check(stick.usages.equals(Arrays.asList(0x30,0x31,0x32,0x35)), "Android sticks map to X/Y/Z/RZ");
        Field buttons = parsed.find(3,8,9,1);
        check(buttons.count == 16 && buttons.size == 1, "sixteen independent button usages");
        int[] androidKeys = {304,305,307,308,310,311,314,315,317,318,312,313,316,306,309,319};
        check(buttons.usages.size() == 16, "each button bit has an explicit usage");
        for (int bit = 0; bit < 16; bit++) check(303 + buttons.usages.get(bit) == androidKeys[bit],
                "UI button bit " + bit + " maps to correct Linux/Android key");
        Field triggers = parsed.find(3,8,2,0xc5);
        check(triggers.min == 0 && triggers.max == 255 && triggers.size == 8 && triggers.count == 2,
                "triggers are unsigned, not accidentally signed logical max -1");
        check(triggers.usages.equals(Arrays.asList(0xc5,0xc4)), "Brake/Gas map to Android left/right trigger axes");
        check((triggers.flags & 4) == 0, "trigger fields are absolute");
        descriptor[0] = 0;
        check(HidReports.descriptor()[0] == 5, "caller cannot mutate descriptor");
    }

    private static final class Field {
        int id, tag, page, size, count, flags, min, max, unit, physicalMax;
        List<Integer> usages;
    }

    private static final class Descriptor {
        final List<Field> fields = new ArrayList<>();
        final Map<String,Integer> bitCounts = new HashMap<>();
        int depth, topLevelCollections;
        Descriptor(byte[] bytes) {
            int id = 0, page = 0, size = 0, count = 0, min = 0, max = 0, unit = 0, physicalMax = 0;
            List<Integer> usages = new ArrayList<>();
            for (int offset = 0; offset < bytes.length;) {
                int prefix = bytes[offset++] & 255;
                check(prefix != 0xfe, "descriptor uses supported short items");
                int length = prefix & 3;
                if (length == 3) length = 4;
                check(offset + length <= bytes.length, "descriptor item is complete");
                int unsigned = 0;
                for (int j = 0; j < length; j++) unsigned |= (bytes[offset++] & 255) << (j * 8);
                int signed = length == 0 ? 0 : unsigned << (32 - length * 8) >> (32 - length * 8);
                int tag = prefix >> 4, type = (prefix >> 2) & 3;
                if (type == 1) {
                    switch (tag) {
                        case 0: page = unsigned; break;
                        case 1: min = signed; break;
                        case 2: max = min < 0 ? signed : unsigned; break;
                        case 4: physicalMax = unsigned; break;
                        case 6: unit = unsigned; break;
                        case 7: size = unsigned; break;
                        case 8: id = unsigned; break;
                        case 9: count = unsigned; break;
                        default: break;
                    }
                } else if (type == 2 && tag == 0) usages.add(unsigned);
                else if (type == 0) {
                    if (tag == 10) { if (depth == 0 && unsigned == 1) topLevelCollections++; depth++; }
                    else if (tag == 12) { depth--; check(depth >= 0, "collection cannot underflow"); }
                    else if (tag == 8 || tag == 9 || tag == 11) {
                        Field field = new Field();
                        field.id = id; field.tag = tag; field.page = page; field.size = size; field.count = count;
                        field.flags = unsigned; field.min = min; field.max = max; field.unit = unit; field.physicalMax = physicalMax;
                        field.usages = new ArrayList<>(usages);
                        fields.add(field);
                        String key = id + ":" + tag;
                        bitCounts.put(key, bitCounts.getOrDefault(key,0) + size * count);
                    }
                    usages.clear();
                }
            }
        }
        int bits(int id, int tag) { return bitCounts.getOrDefault(id + ":" + tag, 0); }
        Field find(int id, int tag, int page, int usage) {
            for (Field field : fields) if (field.id == id && field.tag == tag && field.page == page && field.usages.contains(usage)) return field;
            throw new AssertionError("missing field " + id + ":" + page + ":" + usage);
        }
    }

    private static void eq(String message, int[] expected, byte[] actual) {
        check(actual.length == expected.length, message + " length");
        for (int i = 0; i < expected.length; i++) check((actual[i] & 255) == expected[i], message + " byte " + i);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
