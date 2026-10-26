package com.example.blephotoreceiver;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.net.Uri;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;

public class MainActivity extends Activity {
    // Same UUIDs as the previously successful Espx GATT server test.
    private static final UUID SERVICE_UUID = UUID.fromString("4fa2c730-13a1-11eb-adc1-0242ac120002");
    private static final UUID CHAR_UUID = UUID.fromString("4fa2c974-13a1-11eb-adc1-0242ac120002");
    private static final int REQ = 27;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic characteristic;
    private TextView status, counter, hint;
    private ImageView preview;
    private Button startButton, saveButton;
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int expected = -1;
    private byte[] finishedImage;
    private boolean advertising = false;
    private boolean connected = false;

    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value); }
    private void showStatus(String message) { runOnUiThread(() -> status.setText(message)); }
    private void showCount(String message) { runOnUiThread(() -> counter.setText(message)); }
    private void toast(String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(30), dp(20), dp(20));
        TextView title = new TextView(this);
        title.setText("BLE Photo Receiver"); title.setTextSize(25);
        layout.addView(title);
        hint = new TextView(this);
        hint.setText("手機 = BLE 從端 Espx；ESP32-S3 = BLE 主端\n先按「啟動接收」，再啟動 Arduino。請保持此畫面開啟。\n協定：4 bytes 小端序長度 + JPG 分包寫入");
        hint.setPadding(0, dp(12), 0, dp(12));
        layout.addView(hint);
        startButton = new Button(this); startButton.setText("啟動接收 / 開始廣播");
        layout.addView(startButton);
        status = new TextView(this); status.setText("尚未啟動"); status.setTextSize(17);
        layout.addView(status);
        counter = new TextView(this); counter.setText("0 bytes"); counter.setPadding(0, dp(8), 0, dp(8));
        layout.addView(counter);
        preview = new ImageView(this); preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        saveButton = new Button(this); saveButton.setText("儲存照片到 Pictures"); saveButton.setEnabled(false);
        layout.addView(saveButton);
        setContentView(layout);
        startButton.setOnClickListener(v -> checkPermissionsAndStart());
        saveButton.setOnClickListener(v -> saveImage());
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}, REQ);
            return;
        }
        startPeripheral();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ) {
            boolean granted = grantResults.length == permissions.length;
            for (int r : grantResults) granted &= (r == PackageManager.PERMISSION_GRANTED);
            if (granted) startPeripheral(); else showStatus("需要允許附近裝置權限");
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            showStatus("廣播中：Espx（等待 ESP32-S3）");
        }
        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            showStatus("廣播失敗，錯誤碼：" + errorCode);
        }
    };

    private void startPeripheral() {
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            adapter = manager.getAdapter();
            if (adapter == null) { showStatus("這部手機沒有藍牙功能"); return; }
            if (!adapter.isEnabled()) { showStatus("請先在手機設定中開啟藍牙，再按一次啟動"); return; }
            if (!adapter.isMultipleAdvertisementSupported()) { showStatus("手機不支援 BLE Peripheral 廣播"); return; }
            closeBle();
            adapter.setName("Espx");  // Device name is optional in BLE advertisement; match by UUID.
            server = manager.openGattServer(this, callback);
            if (server == null) { showStatus("無法啟動 GATT Server"); return; }
            BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            characteristic = new BluetoothGattCharacteristic(CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE | BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ);
            service.addCharacteristic(characteristic);
            if (!server.addService(service)) { showStatus("新增 GATT Service 失敗"); return; }
        } catch (SecurityException ex) { showStatus("藍牙權限不足：" + ex.getMessage()); }
        catch (Exception ex) { showStatus("啟動失敗：" + ex.getMessage()); }
    }

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int statusCode, BluetoothGattService service) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { showStatus("GATT Service 初始化失敗：" + statusCode); return; }
            try {
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser == null) { showStatus("找不到 BLE Advertiser"); return; }
                AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
                // 128-bit service UUID goes in advertisement. No name, to avoid 31-byte size limit.
                AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build();
                AdvertiseData scanResponse = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
                advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
            } catch (SecurityException ex) { showStatus("廣播權限不足"); }
        }
        @Override public void onConnectionStateChange(BluetoothDevice device, int statusCode, int newState) {
            connected = (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED);
            showStatus(connected ? "ESP32-S3 已連線，等待圖片..." : "ESP32-S3 已斷線；持續廣播中");
            if (!connected) resetReceiving();
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic c) {
            try { if (server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]); }
            catch (SecurityException ignored) {}
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic c, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            int result;
            if (!CHAR_UUID.equals(c.getUuid()) || preparedWrite || offset != 0) {
                result = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            } else {
                result = receiveChunk(value);
            }
            if (responseNeeded && server != null) {
                try { server.sendResponse(device, requestId, result, 0, null); }
                catch (SecurityException ignored) {}
            }
        }
    };

    private synchronized void resetReceiving() { expected = -1; buffer.reset(); showCount("0 bytes"); }

    private synchronized int receiveChunk(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
        if (expected == -1) {
            if (bytes.length != 4) { showStatus("第一包必須是 4 bytes 圖片長度"); return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH; }
            long len = ((long)bytes[0] & 255) | (((long)bytes[1] & 255) << 8) |
                (((long)bytes[2] & 255) << 16) | (((long)bytes[3] & 255) << 24);
            if (len < 4 || len > MAX_BYTES) { showStatus("圖片大小無效：" + len); return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH; }
            expected = (int)len;
            buffer.reset();
            showStatus("接收圖片中...");
            showCount("0 / " + expected + " bytes");
            return BluetoothGatt.GATT_SUCCESS;
        }
        if (buffer.size() + bytes.length > expected) {
            resetReceiving(); showStatus("資料超過圖片長度，請重新傳送");
            return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
        }
        buffer.write(bytes, 0, bytes.length);
        int received = buffer.size();
        showCount(received + " / " + expected + " bytes");
        if (received == expected) {
            byte[] image = buffer.toByteArray();
            // Verify JPEG SOI/EOI and actual image decode.
            boolean markers = image.length >= 4 && (image[0] & 255) == 0xFF && (image[1] & 255) == 0xD8 &&
                    (image[image.length - 2] & 255) == 0xFF && (image[image.length - 1] & 255) == 0xD9;
            Bitmap bitmap = markers ? BitmapFactory.decodeByteArray(image, 0, image.length) : null;
            if (bitmap == null) { showStatus("已收到 " + received + " bytes，但不是有效 JPG"); }
            else {
                finishedImage = Arrays.copyOf(image, image.length);
                runOnUiThread(() -> { preview.setImageBitmap(bitmap); saveButton.setEnabled(true); status.setText("JPG 接收成功！"); });
            }
            expected = -1;
            buffer.reset();
        }
        return BluetoothGatt.GATT_SUCCESS;
    }

    private void saveImage() {
        if (finishedImage == null) { toast("沒有可儲存的圖片"); return; }
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "BLE_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BLEPhotoReceiver");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("無法建立照片檔案");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) { out.write(finishedImage); }
            toast("已儲存到 Pictures/BLEPhotoReceiver");
        } catch (Exception ex) { toast("儲存失敗：" + ex.getMessage()); }
    }

    private void closeBle() {
        try { if (advertiser != null && advertising) advertiser.stopAdvertising(advertiseCallback); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        advertising = false; server = null; advertiser = null;
    }
    @Override protected void onDestroy() { closeBle(); super.onDestroy(); }
}
