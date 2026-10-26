# BLE Photo Receiver — ESP32-S3 Central → Android Peripheral

Android app is a BLE GATT server/advertiser named `Espx`; ESP32-S3 is BLE Central and writes a **real JPEG**. Phone shows the received picture and can save it to `Pictures/BLEPhotoReceiver`.

## Build Android APK with GitHub Actions

1. Make a new GitHub repository and upload **all contents of this folder** (including hidden `.github/workflows/build-apk.yml`).
2. Open **Actions → Build Android APK → Run workflow**, or push to `main`.
3. On success open the workflow run, download `BLEPhotoReceiver-debug-apk` from Artifacts, unzip and install `app-debug.apk` on the phone. It is a DEBUG build intended for your own testing, not Play Store distribution.
4. Turn Bluetooth on, launch BLE Photo Receiver, grant **Nearby devices** permission, tap **啟動接收 / 開始廣播**. Keep app open.
5. Arduino IDE: open `arduino/ESP32S3_BLE_Photo_Master/ESP32S3_BLE_Photo_Master.ino` and upload (keep `photo_data.h` in same folder). Connect with Serial Monitor at 115200, choose line ending as desired, enter `1`.
6. The phone should show a yellow-circle test picture. Tap **儲存照片到 Pictures** if desired.

## Protocol / constraints

Service `4fa2c730-13a1-11eb-adc1-0242ac120002` and characteristic `4fa2c974-13a1-11eb-adc1-0242ac120002`.

Write With Response, sequentially: first 4 bytes unsigned little-endian JPG length; remaining writes contain up to 20 raw JPG bytes each. No markers or packet sequence number. Phone waits for exactly specified length and decodes the JPEG. Maximum photo size 2 MiB. Keep the app in foreground. Image is only saved when user taps Save. Phone needs BLE peripheral advertising support. All packets must arrive in order; if disconnected, start a fresh send.

**Teaching/demo scope:** Not tested on your actual phone/ESP32-S3, no end-to-end receipt acknowledgment or retransmission, no multi-sender isolation, and Android background operation not implemented. Name in BLE advertisement may not appear because 128-bit Service UUID is prioritized. Match by UUID. Android Bluetooth requires user-approved nearby devices permissions.

If you change the JPEG, generate an updated `photo_data.h` from your file (for example with the companion helper `make_photo_header.py`).
