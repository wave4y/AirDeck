package com.airdeck.hid;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Classic Bluetooth HID Device transport. Public methods and listeners use the main thread.
 * The Activity owns permissions/discoverability and must call releaseAll() on pause/cancel.
 * This controller does not keep an invisible background connection or enable Bluetooth itself.
 */
@SuppressLint("MissingPermission")
public final class HidController {
    private static final String TAG = "AirDeckHid";
    public enum State { OFF, NEED_PERMISSION, UNSUPPORTED, STARTING, READY, CONNECTING, CONNECTED, ERROR }

    public interface Listener {
        void onStateChanged(State state, String detail);
        default void onKeyboardLedsChanged(int leds) { }
    }

    private static final int KEY_HOLD_MS = 24;
    private static final int KEY_GAP_MS = 24;
    private static final int MAX_TEXT_CODEPOINTS = 4096;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences connectionPrefs;
    private final ReconnectPolicy reconnectPolicy;
    private final HidProtocolDiagnostics diagnostics = new HidProtocolDiagnostics();
    private final Object inputToken = new Object();
    private final Set<Integer> heldKeys = new LinkedHashSet<>();
    private final Deque<HidReports.KeyStroke> typingQueue = new ArrayDeque<>();
    private final int[] clickVersions = new int[5];
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice connectedDevice;
    private BluetoothDevice connectingDevice;
    private BluetoothDevice pendingDevice;
    private BluetoothDevice virtualCableDevice;
    private State state = State.OFF;
    private String statusText = "请开启蓝牙";
    private boolean started;
    private boolean receiverRegistered;
    private boolean proxyRequested;
    private boolean registrationPending;
    private boolean registered;
    private boolean foreground = true;
    private boolean reconnectScheduled;
    private boolean automaticConnection;
    private String autoReconnectStatus;
    private String lastDeviceName;
    private byte protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE;
    private int modifiers;
    private int mouseButtons;
    private int clickButtons;
    private int keyboardLeds;
    private int gameButtons;
    private int gameHat = HidReports.HAT_NEUTRAL;
    private int gameLx, gameLy, gameRx, gameRy, gameLt, gameRt;
    private HidReports.KeyStroke typedStroke;
    private long sentReportCount;
    private long failedReportCount;
    private final long[] reportsById = new long[4];

    public HidController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        connectionPrefs = this.context.getSharedPreferences("hid_connection", Context.MODE_PRIVATE);
        reconnectPolicy = new ReconnectPolicy(connectionPrefs.getString("last_address", null));
        lastDeviceName = connectionPrefs.getString("last_name", null);
        BluetoothManager manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public State getState() { return state; }
    public String getStatusText() { return statusText; }
    public boolean isConnected() { return registered && connectedDevice != null && hid != null; }
    public boolean isRegistered() { return registered; }
    public BluetoothDevice getConnectedDevice() { return connectedDevice; }
    public BluetoothDevice getVirtualCableDevice() { return virtualCableDevice; }
    public int getKeyboardLeds() { return keyboardLeds; }
    public boolean isBootProtocol() { return protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE; }
    /** Counts Android transport acceptance, not acknowledgment of processing on the host. */
    public long getSentReportCount() { return sentReportCount; }
    public long getFailedReportCount() { return failedReportCount; }
    /** Null means no detectable problem; it is not proof that the host cached our descriptor. */
    public String getProtocolWarning() { return diagnostics.warning(); }
    public String getLastDeviceName() { return lastDeviceName; }
    public String getAutoReconnectStatus() {
        if (autoReconnectStatus != null) return autoReconnectStatus;
        return lastDeviceName == null ? "首次连接成功后，将记住这台设备" : "下次打开将自动连接 " + lastDeviceName;
    }

    /** Resume retries only in the foreground; a deliberate disconnect remains respected. */
    public void setForeground(boolean value) {
        requireMainThread();
        if (foreground == value) return;
        foreground = value;
        if (!value) {
            cancelAutoReconnect();
            if (automaticConnection && connectingDevice != null && hid != null) {
                try {
                    Log.i(TAG, "Disconnect requested: background automatic attempt");
                    hid.disconnect(connectingDevice);
                }
                catch (SecurityException error) { permissionLost(); }
            }
        }
        else {
            reconnectPolicy.retryAvailable();
            scheduleAutoReconnect();
        }
    }

    public boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Idempotent: safe in onResume and after granting the Nearby Devices permission. */
    public void start() {
        requireMainThread();
        if (!started) reconnectPolicy.newSession();
        started = true;
        if (Build.VERSION.SDK_INT < 28 || adapter == null) {
            update(State.UNSUPPORTED, "此设备不支持 Android 蓝牙 HID");
            return;
        }
        if (!hasConnectPermission()) {
            update(State.NEED_PERMISSION, "需要允许访问附近设备");
            return;
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            // Bluetooth is sent by a privileged system component; the filter is system-only.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(bluetoothReceiver, filter);
            receiverRegistered = true;
        }
        try {
            if (!adapter.isEnabled()) {
                update(State.OFF, "请开启蓝牙");
                return;
            }
            if (registered || registrationPending || proxyRequested) {
                if (registered) scheduleAutoReconnect();
                return;
            }
            update(State.STARTING, "正在启动蓝牙 HID 服务…");
            if (hid != null) registerApplication();
            else {
                proxyRequested = adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE);
                if (proxyRequested) main.postDelayed(startTimeout, 10000);
                else update(State.UNSUPPORTED, "系统未提供蓝牙 HID Device 服务");
            }
        } catch (SecurityException error) {
            permissionLost();
        } catch (RuntimeException error) {
            proxyRequested = false;
            update(State.ERROR, "蓝牙 HID 服务暂时不可用，请重试");
        }
    }

    /** Paired devices are returned without guessing whether a device supports being a HID host. */
    public List<BluetoothDevice> getPairedDevices() {
        if (adapter == null || !hasConnectPermission()) return Collections.emptyList();
        try {
            List<BluetoothDevice> result = new ArrayList<>(adapter.getBondedDevices());
            result.sort(Comparator.comparing(HidController::deviceLabel, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (SecurityException error) {
            return Collections.emptyList();
        }
    }

    public static String deviceLabel(BluetoothDevice device) {
        if (device == null) return "设备";
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? device.getAddress() : name;
        } catch (SecurityException error) { return "蓝牙设备"; }
    }

    /** Returns whether the request was accepted, not whether the connection has completed. */
    public boolean connect(BluetoothDevice device) {
        requireMainThread();
        if (device == null) return false;
        if (!hasConnectPermission()) { permissionLost(); return false; }
        if (!started) start();
        cancelAutoReconnect();
        if (isConnected() && device.equals(connectedDevice)) return true;
        reconnectPolicy.select(device.getAddress());
        automaticConnection = false;
        setAutoReconnectStatus(null);
        pendingDevice = device;
        if (connectedDevice != null || connectingDevice != null) {
            releaseAll();
            BluetoothDevice old = connectedDevice != null ? connectedDevice : connectingDevice;
            try {
                Log.i(TAG, "Disconnect requested: manual host switch");
                if (hid != null && hid.disconnect(old)) {
                    main.removeCallbacks(connectTimeout);
                    main.postDelayed(connectTimeout, 20000);
                    update(State.CONNECTING, "正在切换设备…");
                    return true;
                }
            } catch (SecurityException error) { permissionLost(); return false; }
            pendingDevice = null;
            update(state, "无法切换设备，请先断开当前连接");
            return false;
        }
        if (!registered || hid == null) {
            start();
            return state == State.STARTING;
        }
        return connectPending();
    }

    private boolean connectPending() {
        BluetoothDevice requested = pendingDevice;
        if (requested == null || !registered || hid == null) return false;
        pendingDevice = null;
        try {
            Log.i(TAG, "Requesting HID host connection");
            if (!hid.connect(requested)) {
                automaticConnection = false;
                update(State.READY, "连接请求未成功，请在目标设备蓝牙设置中重试");
                scheduleAutoReconnect();
                return false;
            }
            connectingDevice = requested;
            update(State.CONNECTING, (automaticConnection ? "正在自动连接 " : "正在连接 ") + deviceLabel(requested) + "…");
            main.removeCallbacks(connectTimeout);
            main.postDelayed(connectTimeout, 20000);
            return true;
        } catch (SecurityException error) { permissionLost(); return false; }
    }

    public void disconnect() {
        requireMainThread();
        pendingDevice = null;
        reconnectPolicy.disconnect();
        cancelAutoReconnect();
        automaticConnection = false;
        setAutoReconnectStatus("已暂停自动连接，可点选设备重新连接");
        main.removeCallbacks(connectTimeout);
        releaseAll();
        BluetoothDevice device = connectedDevice != null ? connectedDevice : connectingDevice;
        if (hid == null || device == null) return;
        try {
            Log.i(TAG, "Disconnect requested: manual stop");
            if (!hid.disconnect(device)) update(state, "断开请求未成功，请在系统蓝牙设置中断开");
        } catch (SecurityException error) { permissionLost(); }
    }

    /** Let a new host pair without the remembered host racing it for the HID connection. */
    public void prepareForPairing() {
        requireMainThread();
        start();
        reconnectPolicy.beginPairing();
        cancelAutoReconnect();
        main.removeCallbacks(connectTimeout);
        pendingDevice = null;
        automaticConnection = false;
        releaseAll();
        BluetoothDevice old = connectedDevice != null ? connectedDevice : connectingDevice;
        connectionLost();
        connectedDevice = connectingDevice = virtualCableDevice = null;
        diagnostics.reset();
        autoReconnectStatus = "等待新设备连接，已暂停自动连接旧设备";
        if (old != null && hid != null) {
            try {
                Log.i(TAG, "Disconnect requested: pairing a new host");
                hid.disconnect(old);
            } catch (SecurityException error) { permissionLost(); return; }
        }
        if (registered) update(State.READY, "请在新设备的蓝牙设置中配对并连接");
    }

    private void connectionLost() {
        if (connectedDevice != null)
            reconnectPolicy.disconnected(connectedDevice.getAddress(), SystemClock.elapsedRealtime());
    }

    /** Releases every input, unregisters the SDP application and closes the profile proxy. */
    public void close() {
        requireMainThread();
        releaseAll();
        connectionLost();
        started = false;
        cancelAutoReconnect();
        automaticConnection = false;
        diagnostics.reset();
        main.removeCallbacks(startTimeout);
        main.removeCallbacks(connectTimeout);
        pendingDevice = null;
        if (hid != null) {
            try {
                if (registered || registrationPending) hid.unregisterApp();
                if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
            } catch (SecurityException ignored) { }
        }
        hid = null;
        registered = registrationPending = proxyRequested = false;
        connectedDevice = connectingDevice = virtualCableDevice = null;
        if (receiverRegistered) {
            context.unregisterReceiver(bluetoothReceiver);
            receiverRegistered = false;
        }
        update(State.OFF, "蓝牙控制已停止");
    }

    public void keyDown(int usage) {
        requireMainThread();
        if (!isConnected()) return;
        cancelTyping();
        if (usage >= 0xe0 && usage <= 0xe7) modifiers |= 1 << (usage - 0xe0);
        else if (usage >= 4 && usage <= 0x65) heldKeys.add(usage);
        sendKeyboard();
    }

    public void keyUp(int usage) {
        requireMainThread();
        cancelTyping();
        if (usage >= 0xe0 && usage <= 0xe7) modifiers &= ~(1 << (usage - 0xe0));
        else heldKeys.remove(usage);
        sendKeyboard();
    }

    public void setModifier(int mask, boolean down) {
        requireMainThread();
        if (down && !isConnected()) return;
        cancelTyping();
        modifiers = down ? modifiers | (mask & 255) : modifiers & ~(mask & 255);
        sendKeyboard();
    }

    public void tapKey(int usage, int modifierMask) {
        requireMainThread();
        if (!isConnected() || usage < 0 || usage > 0x65) return;
        typingQueue.addLast(new HidReports.KeyStroke(usage, modifierMask));
        if (typedStroke == null && typingQueue.size() == 1) advanceTyping();
    }

    /**
     * Types at most 4096 mapped characters using US-layout ASCII physical key events.
     * Returns the count of unsupported/over-limit code points, which are skipped explicitly.
     * CRLF is normalized to one Enter. Starting a text entry clears held keyboard keys.
     */
    public int typeText(String text) {
        requireMainThread();
        if (text == null) return 0;
        cancelTyping();
        modifiers = 0;
        heldKeys.clear();
        sendKeyboard();
        int unsupported = 0;
        int count = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r' && offset < text.length() && text.charAt(offset) == '\n') offset++;
            HidReports.KeyStroke stroke = HidReports.forCharacter(codePoint);
            if (stroke == null || count++ >= MAX_TEXT_CODEPOINTS) unsupported++;
            else if (isConnected()) {
                // Caps Lock is controlled by the host; compensate only when typing literal text.
                if (stroke.usage >= 4 && stroke.usage <= 29 && (keyboardLeds & 2) != 0)
                    stroke = new HidReports.KeyStroke(stroke.usage, stroke.modifiers ^ HidReports.MOD_SHIFT);
                typingQueue.addLast(stroke);
            }
        }
        advanceTyping();
        return unsupported;
    }

    public void cancelTyping() {
        requireMainThread();
        main.removeCallbacks(typeRelease);
        main.removeCallbacks(typeNext);
        typingQueue.clear();
        boolean wasPressed = typedStroke != null;
        typedStroke = null;
        if (wasPressed) sendKeyboard();
    }

    private void advanceTyping() {
        if (!isConnected() || typedStroke != null || typingQueue.isEmpty()) return;
        typedStroke = typingQueue.removeFirst();
        if (sendKeyboard()) main.postDelayed(typeRelease, KEY_HOLD_MS);
        else cancelTyping();
    }

    private final Runnable typeNext = this::advanceTyping;
    private final Runnable typeRelease = () -> {
        typedStroke = null;
        if (!sendKeyboard()) { typingQueue.clear(); return; }
        if (!typingQueue.isEmpty()) main.postDelayed(typeNext, KEY_GAP_MS);
    };

    public void mouseMove(int dx, int dy) {
        requireMainThread();
        if (!isConnected()) return;
        // Preserve the full relative distance for high sensitivity, with bounded work per event.
        int remainingX = Math.max(-2032, Math.min(2032, dx));
        int remainingY = Math.max(-2032, Math.min(2032, dy));
        do {
            int x = HidReports.signedAxis(remainingX), y = HidReports.signedAxis(remainingY);
            if (!sendMouse(x, y, 0)) return;
            remainingX -= x;
            remainingY -= y;
        } while (remainingX != 0 || remainingY != 0);
    }

    public void mouseScroll(int wheel) {
        requireMainThread();
        if (isBootProtocol()) return; // Boot mice have no wheel field.
        sendMouse(0, 0, wheel);
    }

    public void mouseButton(int buttonMask, boolean down) {
        requireMainThread();
        if (down && !isConnected()) return;
        mouseButtons = down ? mouseButtons | (buttonMask & 31) : mouseButtons & ~(buttonMask & 31);
        sendMouse(0, 0, 0);
    }

    public void mouseClick(int buttonMask) {
        requireMainThread();
        if (!isConnected()) return;
        final int mask = buttonMask & 31;
        if ((clickButtons & mask) != 0) {
            clickButtons &= ~mask;
            sendMouse(0, 0, 0);
        }
        clickButtons |= mask;
        final int[] versions = clickVersions.clone();
        for (int bit = 0; bit < 5; bit++) if ((mask & (1 << bit)) != 0) versions[bit] = ++clickVersions[bit];
        sendMouse(0, 0, 0);
        main.postAtTime(() -> {
            for (int bit = 0; bit < 5; bit++) {
                if ((mask & (1 << bit)) != 0 && clickVersions[bit] == versions[bit]) clickButtons &= ~(1 << bit);
            }
            sendMouse(0, 0, 0);
        }, inputToken, SystemClock.uptimeMillis() + KEY_HOLD_MS);
    }

    public void gamepadButton(int buttonIndex, boolean down) {
        requireMainThread();
        if (buttonIndex < 0 || buttonIndex > 15 || (down && !isConnected())) return;
        gameButtons = down ? gameButtons | (1 << buttonIndex) : gameButtons & ~(1 << buttonIndex);
        sendGamepad();
    }

    public void gamepadHat(int hat) {
        requireMainThread();
        if (!isConnected()) return;
        gameHat = hat >= 0 && hat <= 7 ? hat : HidReports.HAT_NEUTRAL;
        sendGamepad();
    }

    public void gamepadStick(boolean right, int x, int y) {
        requireMainThread();
        if (!isConnected()) return;
        if (right) { gameRx = HidReports.signedAxis(x); gameRy = HidReports.signedAxis(y); }
        else { gameLx = HidReports.signedAxis(x); gameLy = HidReports.signedAxis(y); }
        sendGamepad();
    }

    public void gamepadTrigger(boolean right, int value) {
        requireMainThread();
        if (!isConnected()) return;
        if (right) gameRt = HidReports.unsignedAxis(value); else gameLt = HidReports.unsignedAxis(value);
        sendGamepad();
    }

    /** All gamepad controls in one atomic report, suitable for a multi-touch gamepad view. */
    public void gamepadState(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt) {
        requireMainThread();
        if (!isConnected()) return;
        gameButtons = buttons & 0xffff;
        gameHat = hat >= 0 && hat <= 7 ? hat : HidReports.HAT_NEUTRAL;
        gameLx = HidReports.signedAxis(lx); gameLy = HidReports.signedAxis(ly);
        gameRx = HidReports.signedAxis(rx); gameRy = HidReports.signedAxis(ry);
        gameLt = HidReports.unsignedAxis(lt); gameRt = HidReports.unsignedAxis(rt);
        sendGamepad();
    }

    /** Also cancels delayed presses/releases so old input cannot reappear after reconnecting. */
    public void releaseAll() {
        requireMainThread();
        clearInputState();
        if (isConnected()) {
            sendKeyboard();
            sendMouse(0, 0, 0);
            sendGamepad();
        }
    }

    private void clearInputState() {
        main.removeCallbacksAndMessages(inputToken);
        main.removeCallbacks(typeRelease);
        main.removeCallbacks(typeNext);
        typingQueue.clear();
        typedStroke = null;
        heldKeys.clear();
        modifiers = mouseButtons = clickButtons = gameButtons = 0;
        gameLx = gameLy = gameRx = gameRy = gameLt = gameRt = 0;
        gameHat = HidReports.HAT_NEUTRAL;
    }

    private byte[] keyboardReport() {
        int allModifiers = modifiers;
        Set<Integer> keys = new LinkedHashSet<>(heldKeys);
        if (typedStroke != null) {
            allModifiers |= typedStroke.modifiers;
            if (typedStroke.usage != 0) keys.add(typedStroke.usage);
        }
        return HidReports.keyboard(allModifiers, keys);
    }

    private byte[] gamepadReport() {
        // Android games accept both analogue trigger axes and L2/R2 key events.
        int buttons = gameButtons;
        if (gameLt >= 128) buttons |= 1 << HidReports.GAME_L2_BIT;
        if (gameRt >= 128) buttons |= 1 << HidReports.GAME_R2_BIT;
        return HidReports.gamepad(buttons, gameHat, gameLx, gameLy, gameRx, gameRy, gameLt, gameRt);
    }

    private boolean sendKeyboard() { return send(HidReports.KEYBOARD_ID, keyboardReport()); }
    private boolean sendMouse(int x, int y, int wheel) {
        int buttons = mouseButtons | clickButtons;
        return send(HidReports.MOUSE_ID, isBootProtocol()
                ? HidReports.bootMouse(buttons, x, y) : HidReports.mouse(buttons, x, y, wheel));
    }
    private boolean sendGamepad() {
        return !isBootProtocol() && send(HidReports.GAMEPAD_ID, gamepadReport());
    }

    private boolean send(int reportId, byte[] report) {
        if (!isConnected()) return false;
        try {
            boolean sent = hid.sendReport(connectedDevice, reportId, report);
            if (sent) {
                sentReportCount++;
                reportsById[reportId]++;
                if (reportsById[reportId] == 1 || sentReportCount % 100 == 0)
                    Log.i(TAG, "sendReport accepted: id=" + reportId + " bytes=" + report.length
                            + " accepted=" + sentReportCount + " failed=" + failedReportCount
                            + " protocol=" + protocol);
            } else {
                failedReportCount++;
                Log.w(TAG, "sendReport rejected: id=" + reportId + " bytes=" + report.length
                        + " accepted=" + sentReportCount + " failed=" + failedReportCount);
                update(state, "输入未送达，请检查连接并重新连接");
            }
            return sent;
        } catch (SecurityException error) {
            failedReportCount++;
            Log.w(TAG, "sendReport denied: id=" + reportId + " bytes=" + report.length);
            permissionLost(); return false;
        }
    }

    private void registerApplication() {
        if (!started || registered || registrationPending || hid == null) return;
        BluetoothHidDeviceAppSdpSettings settings = new BluetoothHidDeviceAppSdpSettings(
                "AirDeck", "Bluetooth keyboard, mouse and gamepad", "AirDeck",
                (byte) (BluetoothHidDevice.SUBCLASS1_COMBO | BluetoothHidDevice.SUBCLASS2_GAMEPAD),
                HidReports.descriptor());
        try {
            registrationPending = true;
            Log.i(TAG, "Registering composite HID: descriptorBytes=" + HidReports.descriptor().length
                    + " descriptorHash=" + Integer.toHexString(Arrays.hashCode(HidReports.descriptor())));
            // registerApp's return value is only IPC acceptance; READY comes from onAppStatusChanged.
            if (!hid.registerApp(settings, null, null, command -> main.post(command), callback)) {
                registrationPending = false;
                update(State.ERROR, "HID 注册失败，请关闭其他蓝牙键鼠应用后重试");
                return;
            }
            main.removeCallbacks(startTimeout);
            main.postDelayed(startTimeout, 10000);
        } catch (SecurityException error) { permissionLost(); }
    }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            main.post(() -> {
                if (profile != BluetoothProfile.HID_DEVICE) return;
                proxyRequested = false;
                main.removeCallbacks(startTimeout);
                if (!started) {
                    if (adapter != null) adapter.closeProfileProxy(profile, proxy);
                    return;
                }
                hid = (BluetoothHidDevice) proxy;
                Log.i(TAG, "HID profile proxy connected");
                registerApplication();
            });
        }
        @Override public void onServiceDisconnected(int profile) {
            main.post(() -> {
                if (profile != BluetoothProfile.HID_DEVICE) return;
                Log.w(TAG, "HID profile proxy disconnected");
                connectionLost();
                clearInputState();
                hid = null;
                registered = registrationPending = proxyRequested = false;
                connectedDevice = connectingDevice = null;
                automaticConnection = false;
                main.removeCallbacks(startTimeout);
                main.removeCallbacks(connectTimeout);
                cancelAutoReconnect();
                diagnostics.reset();
                if (started) update(State.ERROR, "蓝牙 HID 服务已断开，请重试");
            });
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean appRegistered) {
            if (!started || hid == null) return;
            Log.i(TAG, "onAppStatusChanged registered=" + appRegistered + " virtualCable=" + (pluggedDevice != null));
            main.removeCallbacks(startTimeout);
            registered = appRegistered;
            registrationPending = false;
            virtualCableDevice = appRegistered ? pluggedDevice : null;
            if (!appRegistered) {
                connectionLost();
                clearInputState();
                connectedDevice = connectingDevice = null;
                automaticConnection = false;
                main.removeCallbacks(connectTimeout);
                cancelAutoReconnect();
                diagnostics.reset();
                update(adapterEnabled() ? State.ERROR : State.OFF,
                        adapterEnabled() ? "HID 服务已暂停，请返回应用或重试" : "请开启蓝牙");
                return;
            }
            try {
                if (pluggedDevice != null && hid.getConnectionState(pluggedDevice) == BluetoothProfile.STATE_CONNECTED) {
                    onConnectionStateChanged(pluggedDevice, BluetoothProfile.STATE_CONNECTED);
                } else {
                    update(State.READY, reconnectPolicy.isPairing()
                            ? "请在新设备的蓝牙设置中配对并连接" : "已就绪，请连接已配对的另一台安卓设备");
                    if (pendingDevice != null) connectPending();
                    else scheduleAutoReconnect();
                }
            } catch (SecurityException error) { permissionLost(); }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int newState) {
            if (!started || hid == null || device == null) return;
            if (!hasConnectPermission()) { permissionLost(); return; }
            Log.i(TAG, "onConnectionStateChanged state=" + newState + " registered=" + registered
                    + " automatic=" + automaticConnection + " attempts=" + reconnectPolicy.attempts());
            if (newState == BluetoothProfile.STATE_DISCONNECTED || newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    // A queued event from an old attempt must not clear a newer connection.
                    int actual = hid.getConnectionState(device);
                    if (actual != newState) {
                        Log.i(TAG, "Ignoring stale connection callback");
                        // A real quick reconnect can overtake this callback. Its disconnected
                        // interval must still interrupt our continuous-stability measurement.
                        if (newState == BluetoothProfile.STATE_DISCONNECTED && device.equals(connectedDevice)) {
                            connectionLost();
                            clearInputState();
                            if (actual == BluetoothProfile.STATE_CONNECTED) {
                                reconnectPolicy.connected(device.getAddress(), SystemClock.elapsedRealtime());
                            } else if (actual == BluetoothProfile.STATE_CONNECTING) {
                                connectedDevice = null;
                                onConnectionStateChanged(device, actual);
                            }
                        }
                        return;
                    }
                } catch (SecurityException error) { permissionLost(); return; }
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!registered) return;
                String rejection = connectionRejection(device);
                if (rejection == null && device.getBondState() != BluetoothDevice.BOND_BONDED) rejection = "host not bonded";
                if (rejection != null) {
                    Log.i(TAG, "Disconnect requested: " + rejection);
                    try { hid.disconnect(device); }
                    catch (SecurityException error) { permissionLost(); }
                    return;
                }
                if (device.equals(connectedDevice)) return; // Duplicate success cannot renew the retry budget.
                if (!adoptIncoming(device)) return;
                main.removeCallbacks(connectTimeout);
                cancelAutoReconnect();
                connectedDevice = virtualCableDevice = device;
                connectingDevice = null;
                pendingDevice = null;
                automaticConnection = false;
                reconnectPolicy.connected(device.getAddress(), SystemClock.elapsedRealtime());
                lastDeviceName = deviceLabel(device);
                connectionPrefs.edit().putString("last_address", device.getAddress())
                        .putString("last_name", lastDeviceName).apply();
                diagnostics.reset();
                autoReconnectStatus = "下次打开将自动连接 " + lastDeviceName;
                protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE;
                keyboardLeds = 0;
                Arrays.fill(reportsById, 0);
                releaseAll();
                update(State.CONNECTED, "已连接 " + deviceLabel(device));
                if (listener != null) listener.onKeyboardLedsChanged(keyboardLeds);
            } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                if (!registered || device.equals(connectedDevice) || connectionRejection(device) != null) return;
                try {
                    int actual = hid.getConnectionState(device);
                    if (actual == BluetoothProfile.STATE_DISCONNECTED || actual == BluetoothProfile.STATE_DISCONNECTING) {
                        Log.i(TAG, "Ignoring stale connecting callback");
                        return;
                    }
                } catch (SecurityException error) { permissionLost(); return; }
                boolean newAttempt = !device.equals(connectingDevice);
                if (!adoptIncoming(device)) return;
                connectingDevice = device;
                if (newAttempt) {
                    main.removeCallbacks(connectTimeout);
                    main.postDelayed(connectTimeout, 20000);
                }
                update(State.CONNECTING, "正在连接 " + deviceLabel(device) + "…");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
                // A disconnect callback may come from the host; clear delayed input immediately.
                if (device.equals(connectedDevice)) releaseAll();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean current = device.equals(connectedDevice) || device.equals(connectingDevice);
                if (!current) return;
                main.removeCallbacks(connectTimeout);
                connectionLost();
                clearInputState();
                connectedDevice = connectingDevice = null;
                automaticConnection = false;
                if (device.equals(virtualCableDevice)) virtualCableDevice = null;
                diagnostics.reset();
                protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE;
                keyboardLeds = 0;
                if (registered) update(State.READY, "连接已断开，可以重新连接");
                if (listener != null) listener.onKeyboardLedsChanged(keyboardLeds);
                if (pendingDevice != null) connectPending();
                else scheduleAutoReconnect();
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (!validHost(device)) return;
            Log.i(TAG, "onGetReport type=" + type + " id=" + id + " bufferSize=" + bufferSize);
            String warningBefore = diagnostics.warning();
            diagnostics.getReport(type & 255, id & 255);
            notifyProtocolChange(warningBefore);
            byte[] report = null;
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                if (id == HidReports.KEYBOARD_ID) report = keyboardReport();
                else if (id == HidReports.MOUSE_ID) report = isBootProtocol()
                        ? HidReports.bootMouse(mouseButtons | clickButtons, 0, 0)
                        : HidReports.mouse(mouseButtons | clickButtons, 0, 0, 0);
                else if (id == HidReports.GAMEPAD_ID && !isBootProtocol()) report = gamepadReport();
            } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == HidReports.KEYBOARD_ID) {
                report = new byte[]{(byte) keyboardLeds};
            }
            if (report == null) {
                reportError(device, type == BluetoothHidDevice.REPORT_TYPE_FEATURE
                        ? BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ : BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
                return;
            }
            // BufferSize is a host transfer bound and includes the Report ID on the wire.
            // Zero means return the entire report. Never pad report payloads past descriptor size.
            if (bufferSize > 0 && bufferSize < report.length + 1) report = Arrays.copyOf(report, Math.max(0, bufferSize - 1));
            try { hid.replyReport(device, type, id, report); }
            catch (SecurityException error) { permissionLost(); }
        }

        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (!validHost(device)) return;
            Log.i(TAG, "onSetReport type=" + type + " id=" + id + " bytes=" + (data == null ? 0 : data.length));
            String warningBefore = diagnostics.warning();
            diagnostics.output(type & 255, id & 255, data == null ? -1 : data.length);
            notifyProtocolChange(warningBefore);
            if (type != BluetoothHidDevice.REPORT_TYPE_OUTPUT) {
                reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
            } else if (id != HidReports.KEYBOARD_ID) {
                reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            } else if (data == null || data.length != 1) {
                reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM);
            } else {
                acceptKeyboardLeds(data[0]);
                reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
            }
        }

        @Override public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
            if (!validHost(device)) return;
            String warningBefore = diagnostics.warning();
            diagnostics.output(BluetoothHidDevice.REPORT_TYPE_OUTPUT, reportId & 255, data == null ? -1 : data.length);
            notifyProtocolChange(warningBefore);
            if (reportId == HidReports.KEYBOARD_ID && data != null && data.length == 1) acceptKeyboardLeds(data[0]);
        }

        @Override public void onSetProtocol(BluetoothDevice device, byte mode) {
            if (!validHost(device)) return;
            Log.i(TAG, "onSetProtocol mode=" + mode);
            String warningBefore = diagnostics.warning();
            diagnostics.protocol(mode & 255);
            notifyProtocolChange(warningBefore);
            if (mode != BluetoothHidDevice.PROTOCOL_BOOT_MODE && mode != BluetoothHidDevice.PROTOCOL_REPORT_MODE) {
                reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM);
                return;
            }
            clearInputState();
            protocol = mode;
            releaseAll();
            update(State.CONNECTED, "已连接 " + deviceLabel(device) + (isBootProtocol() ? " · 基础键鼠模式" : ""));
        }

        @Override public void onVirtualCableUnplug(BluetoothDevice device) {
            if (!started || !registered || hid == null || device == null) return;
            // Android may deliver DISCONNECTED before UNPLUG, so current transport fields can
            // already be empty. Match the chosen target while rejecting a previous host's event.
            if (!device.getAddress().equals(reconnectPolicy.target())) return;
            Log.i(TAG, "onVirtualCableUnplug");
            releaseAll();
            connectionLost();
            clearInputState();
            pendingDevice = virtualCableDevice = connectedDevice = connectingDevice = null;
            reconnectPolicy.disconnect();
            automaticConnection = false;
            cancelAutoReconnect();
            diagnostics.reset();
            autoReconnectStatus = "目标设备已移除连接，自动连接已暂停";
            main.removeCallbacks(connectTimeout);
            update(registered ? State.READY : State.OFF, "目标设备已移除此 HID 连接，请重新配对或连接");
        }
    };

    /** Explicit choices and active hosts win; an incoming host may supersede a saved-host retry. */
    private String connectionRejection(BluetoothDevice device) {
        if (!reconnectPolicy.accepts(device.getAddress())) return "host selection or manual stop";
        if (connectedDevice != null && !device.equals(connectedDevice)) return "another host already active";
        if (pendingDevice != null && !device.equals(pendingDevice)) return "manual switch pending";
        if (connectingDevice != null && !device.equals(connectingDevice) && !automaticConnection)
            return "another requested host connecting";
        if (!foreground && automaticConnection && device.equals(connectingDevice)) return "background automatic attempt";
        return null;
    }

    private boolean adoptIncoming(BluetoothDevice device) {
        if (device.equals(connectingDevice)) return true;
        if (!reconnectPolicy.selectIncoming(device.getAddress())) return false;
        BluetoothDevice obsolete = connectingDevice;
        connectingDevice = null;
        automaticConnection = false;
        cancelAutoReconnect();
        main.removeCallbacks(connectTimeout);
        if (obsolete != null) {
            try {
                Log.i(TAG, "Disconnect requested: incoming host superseded automatic attempt");
                hid.disconnect(obsolete);
            } catch (SecurityException error) { permissionLost(); return false; }
        }
        return true;
    }

    private boolean validHost(BluetoothDevice device) {
        return started && registered && hid != null && device != null
                && (device.equals(connectedDevice) || device.equals(connectingDevice) || device.equals(virtualCableDevice));
    }

    private void acceptKeyboardLeds(byte value) {
        keyboardLeds = value & 31;
        if (listener != null) listener.onKeyboardLedsChanged(keyboardLeds);
    }

    private void reportError(BluetoothDevice device, byte error) {
        if (hid == null) return;
        try { hid.reportError(device, error); }
        catch (SecurityException permissionError) { permissionLost(); }
    }

    private void notifyProtocolChange(String previous) {
        String warning = diagnostics.warning();
        if (previous == null ? warning != null : !previous.equals(warning)) {
            Log.w(TAG, warning == null ? "Protocol warning cleared" : warning);
            if (listener != null) listener.onStateChanged(state, statusText);
        }
    }

    private void setAutoReconnectStatus(String text) {
        if (autoReconnectStatus == null ? text == null : autoReconnectStatus.equals(text)) return;
        autoReconnectStatus = text;
        if (listener != null) listener.onStateChanged(state, statusText);
    }

    private void cancelAutoReconnect() {
        main.removeCallbacks(autoReconnect);
        reconnectScheduled = false;
    }

    private BluetoothDevice pairedDevice(String address) {
        if (address == null || adapter == null || !hasConnectPermission()) return null;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices())
                if (address.equals(device.getAddress())) return device;
        } catch (SecurityException error) { permissionLost(); }
        return null;
    }

    private void forgetSavedDevice(String address) {
        reconnectPolicy.forget(address);
        if (address != null && address.equals(connectionPrefs.getString("last_address", null))) {
            connectionPrefs.edit().remove("last_address").remove("last_name").apply();
            lastDeviceName = null;
        }
    }

    private void scheduleAutoReconnect() {
        if (!started || !foreground || !registered || hid == null || reconnectScheduled
                || connectedDevice != null || connectingDevice != null || pendingDevice != null
                || !adapterEnabled()) return;
        String address = reconnectPolicy.target();
        if (address == null) return;
        BluetoothDevice device = pairedDevice(address);
        if (device == null) {
            if (!hasConnectPermission()) return;
            forgetSavedDevice(address);
            setAutoReconnectStatus("上次设备已取消配对，请重新配对后连接");
            return;
        }
        long delay = reconnectPolicy.reserveDelay();
        if (delay < 0) {
            setAutoReconnectStatus("自动重连已停止（最多 4 次）。请在接收端允许输入设备，或主动连接本机；然后点选设备重试");
            return;
        }
        reconnectScheduled = true;
        setAutoReconnectStatus("即将自动连接 " + deviceLabel(device) + "（" + reconnectPolicy.attempts()
                + "/" + reconnectPolicy.maxAttempts() + "）");
        main.postDelayed(autoReconnect, delay);
    }

    private final Runnable autoReconnect = this::autoReconnectNow;
    private void autoReconnectNow() {
        reconnectScheduled = false;
        if (!started || !foreground || !registered || hid == null || !adapterEnabled()
                || connectedDevice != null || connectingDevice != null || pendingDevice != null) return;
        String address = reconnectPolicy.target();
        if (address == null) return;
        BluetoothDevice device = pairedDevice(address);
        if (device == null) {
            if (!hasConnectPermission()) return;
            forgetSavedDevice(address);
            setAutoReconnectStatus("上次设备已取消配对，请重新配对后连接");
            return;
        }
        automaticConnection = true;
        pendingDevice = device;
        setAutoReconnectStatus("正在自动连接 " + deviceLabel(device) + "（" + reconnectPolicy.attempts()
                + "/" + reconnectPolicy.maxAttempts() + "）");
        Log.i(TAG, "Auto reconnect attempt " + reconnectPolicy.attempts() + "/" + reconnectPolicy.maxAttempts());
        connectPending();
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!started) return;
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) == BluetoothDevice.BOND_NONE) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null) return;
                    String address = device.getAddress();
                    boolean affected = address.equals(reconnectPolicy.target());
                    forgetSavedDevice(address);
                    if (affected) {
                        cancelAutoReconnect();
                        if (device.equals(pendingDevice)) pendingDevice = null;
                        setAutoReconnectStatus("设备已取消配对，请重新配对后连接");
                    }
                }
                return;
            }
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (newState == BluetoothAdapter.STATE_ON) {
                reconnectPolicy.retryAvailable();
                start();
            }
            else if (newState == BluetoothAdapter.STATE_TURNING_OFF || newState == BluetoothAdapter.STATE_OFF) {
                releaseAll();
                connectionLost();
                registered = registrationPending = proxyRequested = false;
                connectedDevice = connectingDevice = virtualCableDevice = null;
                pendingDevice = null;
                automaticConnection = false;
                main.removeCallbacks(startTimeout);
                main.removeCallbacks(connectTimeout);
                cancelAutoReconnect();
                diagnostics.reset();
                update(State.OFF, "请开启蓝牙");
            }
        }
    };

    private final Runnable startTimeout = () -> {
        if (!started || registered) return;
        proxyRequested = registrationPending = false;
        update(hid == null ? State.UNSUPPORTED : State.ERROR, hid == null
                ? "系统未响应 HID 服务，请确认手机支持蓝牙 HID Device"
                : "HID 注册超时，请关闭其他蓝牙键鼠应用后重试");
    };

    private final Runnable connectTimeout = () -> {
        if (pendingDevice != null) {
            BluetoothDevice old = connectedDevice != null ? connectedDevice : connectingDevice;
            try {
                if (hid != null && (old == null || hid.getConnectionState(old) == BluetoothProfile.STATE_DISCONNECTED)) {
                    connectionLost();
                    clearInputState();
                    connectedDevice = connectingDevice = null;
                    automaticConnection = false;
                    connectPending();
                } else {
                    pendingDevice = null;
                    update(isConnected() ? State.CONNECTED : State.READY, "切换超时，请先断开当前设备后重试");
                }
            } catch (SecurityException error) { permissionLost(); }
            return;
        }
        if (connectedDevice != null || connectingDevice == null) return;
        BluetoothDevice timedOutDevice = connectingDevice;
        connectingDevice = null;
        automaticConnection = false;
        clearInputState();
        try {
            if (hid != null) {
                Log.i(TAG, "Disconnect requested: connection timeout");
                hid.disconnect(timedOutDevice);
            }
        }
        catch (SecurityException error) { permissionLost(); return; }
        update(registered ? State.READY : State.ERROR, "连接超时，请确认目标设备蓝牙已开启并完成配对");
        scheduleAutoReconnect();
    };

    private boolean adapterEnabled() {
        try { return adapter != null && hasConnectPermission() && adapter.isEnabled(); }
        catch (SecurityException error) { return false; }
    }

    private void permissionLost() {
        connectionLost();
        clearInputState();
        registered = registrationPending = proxyRequested = false;
        connectedDevice = connectingDevice = pendingDevice = null;
        automaticConnection = false;
        main.removeCallbacks(startTimeout);
        main.removeCallbacks(connectTimeout);
        cancelAutoReconnect();
        diagnostics.reset();
        update(State.NEED_PERMISSION, "需要允许访问附近设备");
    }

    private void update(State newState, String text) {
        boolean changed = newState != state || !text.equals(statusText);
        if (newState != state) Log.i(TAG, "State " + state + " -> " + newState);
        state = newState;
        statusText = text;
        if (changed && listener != null) listener.onStateChanged(state, statusText);
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("HidController must be called on the main thread");
    }
}
