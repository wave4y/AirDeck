package com.airdeck.hid;

import java.util.HashSet;
import java.util.Set;

/** Pure retry policy. A remembered host is a retry default, not a permanent incoming-host lock. */
final class ReconnectPolicy {
    private static final long[] DELAYS_MS = {600, 2000, 5000, 10000};
    private static final long STABLE_CONNECTION_MS = 30000;
    private String rememberedAddress;
    private String selectedAddress;
    private String budgetAddress;
    private String activeAddress;
    private long connectedAtMs;
    private String pairingOldTarget, pairingOldActive;
    private final Set<String> forgottenAddresses = new HashSet<>();
    private int attempts;
    private boolean suppressed;
    private boolean pairing;
    private boolean targetUnavailable;

    ReconnectPolicy(String rememberedAddress) {
        this.rememberedAddress = budgetAddress = rememberedAddress;
    }
    String target() { return suppressed || pairing || targetUnavailable ? null : selectedAddress != null ? selectedAddress : rememberedAddress; }
    boolean isSuppressed() { return suppressed; }
    boolean isPairing() { return pairing; }
    boolean accepts(String address) {
        return !suppressed && validAddress(address)
                && !forgottenAddresses.contains(address)
                && (!pairing || !address.equals(pairingOldTarget) && !address.equals(pairingOldActive))
                && (selectedAddress == null || selectedAddress.equals(address));
    }
    int attempts() { return attempts; }
    int maxAttempts() { return DELAYS_MS.length; }

    void newSession() {
        selectedAddress = activeAddress = null;
        budgetAddress = rememberedAddress;
        attempts = 0;
        suppressed = false;
        targetUnavailable = false;
        forgottenAddresses.clear();
        endPairing();
    }
    void select(String address) {
        if (!validAddress(address)) return;
        selectedAddress = budgetAddress = address;
        attempts = 0;
        suppressed = false;
        targetUnavailable = false;
        forgottenAddresses.remove(address);
        endPairing();
    }
    /** Claim an incoming attempt once. Duplicate CONNECTING callbacks cannot renew its budget. */
    boolean selectIncoming(String address) {
        if (!accepts(address)) return false;
        useBudgetFor(address);
        selectedAddress = address;
        targetUnavailable = false;
        endPairing();
        return true;
    }
    /** A short-lived CONNECTED is not evidence of a stable connection. */
    void connected(String address, long nowMs) {
        if (!accepts(address)) return;
        useBudgetFor(address);
        if (!address.equals(activeAddress)) {
            activeAddress = address;
            connectedAtMs = nowMs;
        }
        rememberedAddress = address;
        selectedAddress = null;
        targetUnavailable = false;
        endPairing();
    }
    /** Only the current connection can restore its own budget after 30 uninterrupted seconds. */
    void disconnected(String address, long nowMs) {
        if (address == null || !address.equals(activeAddress)) return;
        if (address.equals(budgetAddress) && nowMs >= connectedAtMs
                && nowMs - connectedAtMs >= STABLE_CONNECTION_MS) attempts = 0;
        activeAddress = null;
        connectedAtMs = 0;
    }
    void beginPairing() {
        pairingOldTarget = selectedAddress != null ? selectedAddress : rememberedAddress;
        pairingOldActive = activeAddress;
        selectedAddress = null;
        suppressed = false;
        forgottenAddresses.clear();
        pairing = true;
    }
    void disconnect() { suppressed = true; endPairing(); }
    /** Availability changes allow scheduling again, but never replenish a failed retry budget. */
    void retryAvailable() { }
    void forget(String address) {
        if (address == null) return;
        boolean selected = address.equals(selectedAddress);
        boolean remembered = address.equals(rememberedAddress);
        if (pairing) {
            // Explicit pairing may include forgetting and re-pairing the same host. The
            // controller still requires BOND_BONDED before accepting a completed connection.
            forgottenAddresses.remove(address);
            if (address.equals(pairingOldTarget)) pairingOldTarget = null;
            if (address.equals(pairingOldActive)) pairingOldActive = null;
        } else forgottenAddresses.add(address);
        // Do not fall back to an older saved host after forgetting an explicit target.
        // This pauses only outbound attempts; another paired incoming host is still allowed.
        if (selected || remembered && selectedAddress == null) targetUnavailable = true;
        if (remembered) {
            rememberedAddress = null;
        }
        if (selected) selectedAddress = null;
        if (address.equals(activeAddress)) { activeAddress = null; connectedAtMs = 0; }
        if (address.equals(budgetAddress)) { budgetAddress = null; attempts = 0; }
    }
    long reserveDelay() {
        if (target() == null || attempts >= DELAYS_MS.length) return -1;
        return DELAYS_MS[attempts++];
    }
    private void useBudgetFor(String address) {
        if (!address.equals(budgetAddress)) { budgetAddress = address; attempts = 0; }
    }
    private void endPairing() { pairing = false; pairingOldTarget = pairingOldActive = null; }
    private static boolean validAddress(String address) { return address != null && !address.isEmpty(); }
}
