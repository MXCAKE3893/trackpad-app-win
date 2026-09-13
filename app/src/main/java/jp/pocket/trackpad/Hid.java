package jp.pocket.trackpad;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import java.util.function.Consumer;

/** Bluetooth Classic HID: relative mouse (report 1) and keyboard (report 2). */
@SuppressLint("MissingPermission")
final class Hid implements AutoCloseable {
    private final Context context;
    final BluetoothAdapter adapter;
    private final Consumer<String> status;
    private BluetoothHidDevice proxy;
    private BluetoothDevice host;
    private boolean registered, closed;
    private boolean opening, registering;
    Runnable onReady = () -> {};
    private int buttons;
    private byte[] keyboard = new byte[8];

    // Mouse: buttons, signed 16-bit X/Y, signed 8-bit wheel and AC Pan.
    private static final byte[] DESCRIPTOR = bytes(
        0x05,1,0x09,2,0xA1,1,0x85,1,0x09,1,0xA1,0,
        0x05,9,0x19,1,0x29,3,0x15,0,0x25,1,0x75,1,0x95,3,0x81,2,
        0x75,5,0x95,1,0x81,3,
        0x05,1,0x09,0x30,0x09,0x31,0x16,1,0x80,0x26,0xFF,0x7F,
        0x75,16,0x95,2,0x81,6,
        0x09,0x38,0x15,0x81,0x25,0x7F,0x75,8,0x95,1,0x81,6,
        0x05,0x0C,0x0A,0x38,2,0x95,1,0x81,6,0xC0,0xC0,
        0x05,1,0x09,6,0xA1,1,0x85,2,
        0x05,7,0x19,0xE0,0x29,0xE7,0x15,0,0x25,1,0x75,1,0x95,8,0x81,2,
        0x75,8,0x95,1,0x81,3,
        0x05,8,0x19,1,0x29,5,0x75,1,0x95,5,0x91,2,0x75,3,0x95,1,0x91,3,
        0x05,7,0x19,0,0x29,0x65,0x15,0,0x25,0x65,0x75,8,0x95,6,0x81,0,0xC0);

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    Hid(Context context, Consumer<String> status) {
        this.context = context;
        this.status = status;
        adapter = context.getSystemService(BluetoothManager.class).getAdapter();
    }

    void start() {
        if (adapter == null || !adapter.isEnabled()) { status.accept("Bluetoothをオンにしてください"); return; }
        if (registered) { onReady.run(); return; }
        if (proxy != null) { register(); return; }
        if (opening) return;
        opening = adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE);
        if (!opening)
            status.accept("この端末はBluetooth HIDに対応していません");
    }

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile service) {
            opening = false;
            if (closed) { adapter.closeProfileProxy(profile, service); return; }
            proxy = (BluetoothHidDevice) service;
            register();
        }
        public void onServiceDisconnected(int profile) {
            opening = registering = false;
            proxy = null; registered = false; host = null;
            status.accept("Bluetoothサービスが切断されました。再接続してください");
        }
    };

    private void register() {
        if (registered || registering || closed) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
            "Pocket Trackpad", "Android Bluetooth trackpad", "Pocket Trackpad",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        registering = proxy.registerApp(sdp, null, null, context.getMainExecutor(), callback);
        if (!registering)
            status.accept("HID登録に失敗しました。ほかのHIDアプリを終了してください");
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice device, boolean ready) {
            registering = false;
            registered = ready;
            if (!ready) { host = null; buttons = 0; keyboard = new byte[8]; }
            status.accept(ready ? "準備完了：PCを選択して接続してください" : "HID停止：接続準備を押してください");
            if (ready) onReady.run();
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device; release(); status.accept("接続中：" + device.getName());
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(host)) host = null;
                buttons = 0; keyboard = new byte[8]; status.accept("切断されました：PCを選択して再接続");
            } else if (state == BluetoothProfile.STATE_CONNECTING) status.accept("接続しています…");
        }
        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int size) {
            byte[] report = null;
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                if (id == 1) report = bytes(buttons,0,0,0,0,0,0);
                if (id == 2) report = keyboard.clone();
            } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == 2) report = new byte[1];
            if (report == null) proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            else if (size > 0 && size < report.length) proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM);
            else proxy.replyReport(device, type, id, report);
        }
        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            proxy.reportError(device, type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == 2
                ? BluetoothHidDevice.ERROR_RSP_SUCCESS : BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
        }
    };

    void connect(BluetoothDevice device) {
        if (!registered || proxy == null) { status.accept("先に接続準備を完了してください"); return; }
        if (host != null && !host.equals(device)) { status.accept("先に現在のPCから切断してください"); return; }
        if (!proxy.connect(device)) status.accept("接続要求に失敗しました。Windows側のペアリングを確認してください");
    }

    private void send(int id, byte[] data) {
        if (host != null && proxy != null && !proxy.sendReport(host, id, data))
            status.accept("送信失敗：接続状態を確認してください");
    }
    void mouse(int x, int y, int wheel, int pan) {
        x = Math.max(-32767, Math.min(32767, x)); y = Math.max(-32767, Math.min(32767, y));
        send(1, bytes(buttons, x, x >> 8, y, y >> 8,
            Math.max(-127, Math.min(127, wheel)), Math.max(-127, Math.min(127, pan))));
    }
    void button(int mask, boolean down) { buttons = down ? buttons | mask : buttons & ~mask; mouse(0,0,0,0); }
    void click(int mask) { button(mask, true); button(mask, false); }
    void keys(int modifier, int key) { keyboard = bytes(modifier,0,key,0,0,0,0,0); send(2,keyboard); }
    void shortcut(int modifier, int key) { keys(modifier,key); keys(0,0); }
    void zoom(int steps) { keys(1,0); mouse(0,0,steps,0); keys(0,0); }
    void release() { buttons = 0; mouse(0,0,0,0); keys(0,0); }
    void disconnect() { release(); if (host != null && proxy != null) proxy.disconnect(host); }
    @Override public void close() {
        closed = true; release();
        if (proxy != null) { proxy.unregisterApp(); adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE,proxy); proxy = null; }
    }
}
