import com.airdeck.hid.HidReports;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Compare a receiver's sysfs report_descriptor with the descriptor registered by this app. */
public final class CompareDescriptor {
    public static void main(String[] args) throws Exception {
        byte[] expected = HidReports.descriptor();
        print("AirDeck", expected);
        if (args.length == 0) return;
        byte[] actual = Files.readAllBytes(Paths.get(args[0]));
        print("Receiver", actual);
        if (Arrays.equals(expected, actual)) {
            System.out.println("MATCH: Receiver HID report descriptor is byte-for-byte identical.");
        } else {
            int offset = 0;
            while (offset < Math.min(expected.length, actual.length) && expected[offset] == actual[offset]) offset++;
            System.out.println("MISMATCH: first difference at byte " + offset + "; compare report IDs before changing sender.");
            System.exit(1);
        }
    }

    private static void print(String label, byte[] bytes) {
        System.out.println(label + ": bytes=" + bytes.length + " javaHash=" + Integer.toHexString(Arrays.hashCode(bytes)));
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format("%02x", value & 255));
        System.out.println(hex);
    }
}
