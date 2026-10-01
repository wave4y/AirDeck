package com.airdeck.hid;

public final class HidConnectionPolicyTest {
    private static int assertions;
    private static void equal(Object expected, Object actual, String label) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        ReconnectPolicy policy = new ReconnectPolicy("saved");
        equal("saved", policy.target(), "restore saved host");
        long[] delays = {600L, 2000L, 5000L, 10000L};
        for (long delay : delays) equal(delay, policy.reserveDelay(), "bounded backoff");
        equal(-1L, policy.reserveDelay(), "stop after retry budget");
        equal(-1L, policy.reserveDelay(), "duplicate failure cannot restart budget");
        policy.select("new");
        equal("new", policy.target(), "manual target takes priority");
        equal(600L, policy.reserveDelay(), "manual target gets new retry budget");
        for (int i = 0; i < 5; i++) policy.reserveDelay();
        equal("new", policy.target(), "failed explicit connection never falls back to old device");
        check(!policy.accepts("saved"), "late old-host connection cannot override manual selection");
        check(policy.accepts("new"), "selected device may finish connecting");
        policy.disconnect();
        equal(null, policy.target(), "manual disconnect prevents reconnect");
        check(!policy.accepts("new"), "late connection after deliberate disconnect is rejected");
        policy.retryAvailable();
        equal(-1L, policy.reserveDelay(), "foreground or Bluetooth return preserves manual stop");
        policy.select("new");
        equal("new", policy.target(), "manual connect resumes");
        policy.connected("new");
        equal(600L, policy.reserveDelay(), "unexpected disconnect gets fresh budget after success");
        policy.newSession();
        equal("new", policy.target(), "new app session uses latest successful host");
        policy.select("unpaired");
        policy.forget("unpaired");
        equal(null, policy.target(), "unpairing explicit target cannot reconnect to old host");
        policy.newSession();
        equal("new", policy.target(), "old successful host remains until replaced by success");
        policy.forget("new");
        equal(null, policy.target(), "unpaired remembered device removed");
        check(!policy.accepts("new"), "late connection cannot remember an unpaired host again");
        equal(-1L, policy.reserveDelay(), "cannot retry without a remembered device");
        policy = new ReconnectPolicy("saved");
        policy.select("new");
        policy.reserveDelay();
        policy.forget("unrelated");
        equal(1, policy.attempts(), "unpairing unrelated device preserves retry budget");
        policy.forget("saved");
        equal("new", policy.target(), "unpairing old host does not suppress selected new host");
        equal(1, policy.attempts(), "unpairing old host preserves selected target's budget");

        HidProtocolDiagnostics diagnostic = new HidProtocolDiagnostics();
        equal(null, diagnostic.warning(), "unknown host cache is not an error");
        for (int id = 1; id <= 3; id++) diagnostic.getReport(1, id);
        diagnostic.getReport(2, 1);
        diagnostic.getReport(3, 0);
        diagnostic.output(2, 1, 1);
        diagnostic.output(3, 0, 0);
        equal(null, diagnostic.warning(), "valid reports, LED feedback and feature probes are normal");
        diagnostic.protocol(0);
        check(diagnostic.warning().contains("手柄和鼠标滚轮不可用"), "boot mode explicitly warns about limitations");
        diagnostic.getReport(1, 1);
        diagnostic.getReport(1, 2);
        diagnostic.output(2, 1, 1);
        check(!diagnostic.warning().contains("缓存"), "boot reports must not claim a stale cache");
        diagnostic.protocol(1);
        equal(null, diagnostic.warning(), "return to report protocol clears boot-only warning");
        diagnostic.getReport(2, 2);
        check(diagnostic.warning().contains("类型 2，编号 2"), "old swapped keyboard report ID detected");
        check(diagnostic.warning().contains("可能"), "host cache mismatch is a possibility, not proof");
        diagnostic.output(2, 1, 1);
        check(diagnostic.warning() != null, "one good LED packet cannot hide earlier incompatibility");
        diagnostic.reset();
        equal(null, diagnostic.warning(), "new connection clears stale evidence");
        diagnostic.output(2, 1, 8);
        check(diagnostic.warning().contains("长度 8"), "invalid output length detected");
        diagnostic.reset();
        diagnostic.output(2, 1, -1);
        check(diagnostic.warning() != null, "null output rejected");
        diagnostic.reset();
        diagnostic.protocol(255);
        check(diagnostic.warning().contains("未知协议模式"), "unknown mode detected");
        diagnostic.reset();
        diagnostic.getReport(0, 1);
        check(diagnostic.warning() != null, "invalid report type detected");
        System.out.println("PASS: " + assertions + " connection/diagnostic assertions");
    }
}
