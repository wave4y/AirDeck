package com.airdeck.hid;

/** Checks evidence visible to a HID sender; it cannot inspect the host's cached descriptor. */
final class HidProtocolDiagnostics {
    private boolean boot;
    private String anomaly;

    void reset() { boot = false; anomaly = null; }
    String warning() {
        String mode = boot ? "接收端切换到了基础键鼠协议，手柄和鼠标滚轮不可用。请重新连接；仍有异常时查看设备页的修复说明。" : null;
        return anomaly == null ? mode : mode == null ? anomaly : mode + "\n" + anomaly;
    }
    void protocol(int mode) {
        if (mode == 0 || mode == 1) boot = mode == 0;
        else mismatch("接收端请求了未知协议模式（" + mode + "）");
    }
    void getReport(int type, int id) {
        // Feature probes are allowed to fail; unsupported features alone do not imply a mismatch.
        if (type == 3) return;
        if (type == 1 && (id == 1 || id == 2 || (!boot && id == 3))) return;
        if (type == 2 && id == 1) return;
        mismatch("接收端请求的报告与当前协议不符（类型 " + type + "，编号 " + id + "）");
    }
    void output(int type, int id, int length) {
        if (type == 3) return; // Generic feature probes are not evidence of a stale descriptor.
        if (type == 2 && id == 1 && length == 1) return; // Normal keyboard LED feedback.
        mismatch("接收端反馈的报告与当前协议不符（类型 " + type + "，编号 " + id + "，长度 " + length + "）");
    }
    private void mismatch(String detail) {
        anomaly = detail + "。可能存在旧的 HID 配对缓存或兼容性问题；请查看设备页的协议修复说明。";
    }
}
