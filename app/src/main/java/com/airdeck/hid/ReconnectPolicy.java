package com.airdeck.hid;

/** Pure retry policy: an explicit choice wins over the saved host for the whole session. */
final class ReconnectPolicy {
    private static final long[] DELAYS_MS = {600, 2000, 5000, 10000};
    private String rememberedAddress;
    private String selectedAddress;
    private int attempts;
    private boolean suppressed;

    ReconnectPolicy(String rememberedAddress) { this.rememberedAddress = rememberedAddress; }
    String target() { return suppressed ? null : selectedAddress != null ? selectedAddress : rememberedAddress; }
    boolean isSuppressed() { return suppressed; }
    boolean accepts(String address) {
        return !suppressed && address != null && (selectedAddress == null || selectedAddress.equals(address));
    }
    int attempts() { return attempts; }
    int maxAttempts() { return DELAYS_MS.length; }

    void newSession() { selectedAddress = null; attempts = 0; suppressed = false; }
    void select(String address) { selectedAddress = address; attempts = 0; suppressed = false; }
    void connected(String address) { rememberedAddress = selectedAddress = address; attempts = 0; }
    void disconnect() { suppressed = true; attempts = 0; }
    void retryAvailable() { attempts = 0; }
    void forget(String address) {
        if (address == null) return;
        boolean selected = address.equals(selectedAddress);
        boolean remembered = address.equals(rememberedAddress);
        if (remembered) {
            rememberedAddress = null;
            if (selectedAddress == null) suppressed = true;
        }
        if (selected) { selectedAddress = null; suppressed = true; }
        if (selected || (remembered && selectedAddress == null)) attempts = 0;
    }
    long reserveDelay() {
        if (target() == null || attempts >= DELAYS_MS.length) return -1;
        return DELAYS_MS[attempts++];
    }
}
