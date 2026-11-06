package com.swimhr.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQ_PERMISSIONS = 77;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothGatt> gatts = new HashMap<>();
    private final Map<String, DeviceCard> cards = new HashMap<>();
    private final Set<String> connecting = new HashSet<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private LinearLayout root;
    private GridLayout grid;
    private TextView summary;
    private Button scanButton;
    private Button timerButton;
    private long timerStartedAt = 0L;
    private boolean timerRunning = false;
    private final Runnable timerTicker = new Runnable() {
        @Override public void run() {
            if (timerRunning) {
                timerButton.setText("训练计时  " + formatElapsed(System.currentTimeMillis() - timerStartedAt));
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        requestBlePermissions();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(242, 245, 249));

        TextView title = new TextView(this);
        title.setText("游泳队 · 实时心率监测");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(25, 42, 65));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        summary = new TextView(this);
        summary.setText("尚未连接设备");
        summary.setTextSize(13);
        summary.setTextColor(Color.DKGRAY);
        summary.setPadding(0, dp(3), 0, dp(6));
        root.addView(summary);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        scanButton = makeButton("开始扫描");
        scanButton.setOnClickListener(v -> { if (scanning) stopScan(); else startScan(); });
        controls.addView(scanButton, weighted());
        Button clear = makeButton("清除设备");
        clear.setOnClickListener(v -> clearDevices());
        controls.addView(clear, weighted());
        timerButton = makeButton("开始训练计时");
        timerButton.setOnClickListener(v -> toggleTimer());
        controls.addView(timerButton, weighted());
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(46)));

        ScrollView scroll = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        refreshSummary();
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        return b;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void requestBlePermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMISSIONS) {
            boolean ok = true;
            for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) ok = false;
            if (!ok) toast("需要蓝牙扫描和连接权限才能使用");
        }
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (!hasPermissions()) { requestBlePermissions(); return; }
        if (adapter == null || !adapter.isEnabled()) { toast("请先打开手机蓝牙"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("当前设备无法启动蓝牙扫描"); return; }
        try {
            scanner.startScan(scanCallback);
            scanning = true;
            scanButton.setText("停止扫描");
            summary.setText("正在扫描心率设备… 已连接 " + gatts.size() + " 台");
            handler.postDelayed(() -> { if (scanning) stopScan(); }, 20000);
        } catch (SecurityException e) { toast("没有蓝牙扫描权限"); }
    }

    private void stopScan() {
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
        if (scanButton != null) scanButton.setText("开始扫描");
        refreshSummary();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String address;
            String name;
            try {
                address = device.getAddress();
                name = device.getName();
            } catch (SecurityException e) { return; }
            if (address == null || gatts.containsKey(address) || connecting.contains(address)) return;
            boolean advertisesHeartRate = result.getScanRecord() != null
                    && result.getScanRecord().getServiceUuids() != null
                    && result.getScanRecord().getServiceUuids().stream().anyMatch(u -> HEART_RATE_SERVICE.equals(u.getUuid()));
            if (!advertisesHeartRate) return;
            final String deviceName = (name == null || name.trim().isEmpty()) ? "心率设备" : name.trim();
            connecting.add(address);
            addCard(address, deviceName, "正在连接…");
            try {
                BluetoothGatt gatt;
                if (Build.VERSION.SDK_INT >= 23) gatt = device.connectGatt(MainActivity.this, false, new HeartRateGattCallback(address), BluetoothDevice.TRANSPORT_LE);
                else gatt = device.connectGatt(MainActivity.this, false, new HeartRateGattCallback(address));
                if (gatt != null) gatts.put(address, gatt);
                else { connecting.remove(address); setCardStatus(address, "连接失败"); }
            } catch (SecurityException e) {
                connecting.remove(address);
                setCardStatus(address, "蓝牙权限不足");
            }
            refreshSummary();
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            runOnUiThread(() -> { scanButton.setText("开始扫描"); toast("扫描失败，错误码 " + errorCode); });
        }
    };

    private class HeartRateGattCallback extends BluetoothGattCallback {
        private final String address;
        HeartRateGattCallback(String address) { this.address = address; }

        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connecting.remove(address);
                setCardStatus(address, "已连接，正在查找心率服务…");
                try { gatt.discoverServices(); } catch (SecurityException e) { setCardStatus(address, "服务发现权限错误"); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connecting.remove(address);
                gatts.remove(address);
                setCardStatus(address, "已断开");
                try { gatt.close(); } catch (Exception ignored) {}
                refreshSummary();
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting.remove(address);
                setCardStatus(address, "连接异常 (" + status + ")");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { setCardStatus(address, "服务发现失败 (" + status + ")"); return; }
            BluetoothGattService service = gatt.getService(HEART_RATE_SERVICE);
            BluetoothGattCharacteristic characteristic = service == null ? null : service.getCharacteristic(HEART_RATE_MEASUREMENT);
            if (characteristic == null) { setCardStatus(address, "未找到标准心率特征"); return; }
            try {
                boolean enabled = gatt.setCharacteristicNotification(characteristic, true);
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
                if (!enabled || descriptor == null) { setCardStatus(address, "开启心率通知失败"); return; }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) started = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
                else { started = gatt.writeDescriptor(descriptor); }
                if (!started) setCardStatus(address, "通知订阅请求失败");
                else setCardStatus(address, "已连接，等待心率数据…");
            } catch (SecurityException e) { setCardStatus(address, "蓝牙权限错误"); }
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD.equals(descriptor.getUuid())) setCardStatus(address, status == BluetoothGatt.GATT_SUCCESS ? "心率通知已开启，等待数据…" : "心率通知开启失败 (" + status + ")");
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                if (value != null) handleHeartRate(address, value);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (HEART_RATE_MEASUREMENT.equals(characteristic.getUuid()) && value != null) handleHeartRate(address, value);
        }
    }

    private void handleHeartRate(String address, byte[] data) {
        if (data.length < 2) return;
        int flags = data[0] & 0xff;
        int bpm;
        if ((flags & 0x01) != 0) {
            if (data.length < 3) return;
            bpm = (data[1] & 0xff) | ((data[2] & 0xff) << 8);
        } else bpm = data[1] & 0xff;
        if (bpm <= 0 || bpm > 250) return;
        runOnUiThread(() -> {
            DeviceCard card = cards.get(address);
            if (card != null) {
                card.bpm.setText(String.valueOf(bpm));
                card.status.setText("实时接收中 · " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date()));
                card.container.setBackgroundColor(Color.rgb(225, 246, 235));
            }
            refreshSummary();
        });
    }

    private void addCard(String address, String deviceName, String status) {
        runOnUiThread(() -> {
            if (cards.containsKey(address)) return;
            DeviceCard card = new DeviceCard(address, deviceName);
            card.status.setText(status);
            cards.put(address, card);
            int width = getResources().getDisplayMetrics().widthPixels;
            int columns = width >= dp(900) ? 3 : 2;
            grid.setColumnCount(columns);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(116);
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(card.container, lp);
            refreshSummary();
        });
    }

    private class DeviceCard {
        final LinearLayout container;
        final TextView name;
        final TextView bpm;
        final TextView status;
        final String address;
        DeviceCard(String address, String deviceName) {
            this.address = address;
            container = new LinearLayout(MainActivity.this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp(12), dp(8), dp(12), dp(8));
            container.setBackgroundColor(Color.WHITE);
            name = new TextView(MainActivity.this);
            name.setText(deviceName);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setTextColor(Color.rgb(35, 48, 65));
            bpm = new TextView(MainActivity.this);
            bpm.setText("--");
            bpm.setTextSize(30);
            bpm.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            bpm.setTextColor(Color.rgb(20, 110, 75));
            status = new TextView(MainActivity.this);
            status.setTextSize(11);
            status.setTextColor(Color.DKGRAY);
            container.addView(name);
            container.addView(bpm);
            container.addView(status);
            container.setOnLongClickListener(v -> { renameDevice(address); return true; });
        }
    }

    private void renameDevice(String address) {
        DeviceCard card = cards.get(address);
        if (card == null) return;
        EditText input = new EditText(this);
        input.setText(card.name.getText());
        new AlertDialog.Builder(this).setTitle("修改运动员姓名").setView(input)
                .setPositiveButton("保存", (d, w) -> card.name.setText(input.getText().toString().trim().isEmpty() ? "运动员" : input.getText().toString().trim()))
                .setNegativeButton("取消", null).show();
    }

    private void setCardStatus(String address, String text) {
        runOnUiThread(() -> {
            DeviceCard card = cards.get(address);
            if (card != null) {
                card.status.setText(text);
                if (text.contains("失败") || text.contains("断开") || text.contains("异常")) card.container.setBackgroundColor(Color.rgb(255, 235, 235));
            }
            refreshSummary();
        });
    }

    private void clearDevices() {
        stopScan();
        for (BluetoothGatt gatt : new ArrayList<>(gatts.values())) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
        }
        gatts.clear();
        connecting.clear();
        cards.clear();
        grid.removeAllViews();
        refreshSummary();
    }

    private void toggleTimer() {
        if (!timerRunning) {
            timerRunning = true;
            timerStartedAt = System.currentTimeMillis();
            timerButton.setText("训练计时  00:00:00");
            handler.post(timerTicker);
        } else {
            timerRunning = false;
            handler.removeCallbacks(timerTicker);
            timerButton.setText("重新开始计时");
        }
    }

    private String formatElapsed(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    private void refreshSummary() {
        if (summary != null) summary.setText("设备 " + cards.size() + " 台  ·  已建立连接 " + gatts.size() + " 台  ·  长按卡片可修改姓名");
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        stopScan();
        handler.removeCallbacksAndMessages(null);
        for (BluetoothGatt gatt : new ArrayList<>(gatts.values())) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
        }
        gatts.clear();
        super.onDestroy();
    }
}
