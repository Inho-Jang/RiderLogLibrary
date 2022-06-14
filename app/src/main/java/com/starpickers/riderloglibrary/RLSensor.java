package com.starpickers.riderloglibrary;

import android.Manifest;
import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import no.nordicsemi.android.dfu.BuildConfig;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

public class RLSensor extends DfuBaseService {
    // System msg
    public final static int SENSOR_MSG_CHECK_CONNECTION = 0x0000;
    public final static int SENSOR_MSG_SYSTEM_STATUS = 0x0001;
    public final static int SENSOR_MSG_REQUEST_FIRM_VERSION = 0x0002;
    public final static int SENSOR_MSG_REQUEST_FIRM_UPDATE = 0x0003;
    public final static int SENSOR_MSG_REQUEST_REBOOT = 0x0004;
    public final static int SENSOR_MSG_REQUEST_ALL_CALI = 0x0010;
    public final static int SENSOR_MSG_REQUEST_ACCEL_CALI = 0x0011;
    public final static int SENSOR_MSG_REQUEST_GYRO_CALI = 0x0012;
    public final static int SENSOR_MSG_REQUEST_ORIGIN_CALI = 0x0014;
    public final static int SENSOR_MSG_REQUEST_RESET = 0xFFFF;

    // User Msg
    public final static int SERVER_MSG_REQUEST_USER_ID = 0x0010;
    public final static int SERVER_MSG_REQUEST_USER_NAME = 0x0011;
    public final static int SERVER_MSG_REQUEST_USER_EMAIL = 0x0012;
    public final static int SERVER_MSG_REQUEST_USER_MOBILE = 0x0013;

    // Data Msg
    public final static int SENSOR_MSG_IMU = 0x0100;
    public final static int SENSOR_MSG_ATT = 0x0101;
    public final static int SENSOR_MSG_GNSS = 0x0102;
    public final static int SENSOR_MSG_MAG = 0x0103;
    public final static int SENSOR_MSG_TEMP = 0x0104;
    public final static int SENSOR_MSG_BARO = 0x0105;

    // Event Msg
    public final static int SENSOR_MSG_EVNT = 0x0201;

    // BLE 관련 클래스
    private final static String uuid_service = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    private final static String uuid_character_tx = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    private final static String uuid_character_rx = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
    private final static String uuid_descriptor = "000002902-0000-1000-8000-00805f9b34fb";
    private static BluetoothAdapter bluetoothAdapter;
    private static BluetoothGatt bluetoothGatt;
    private static BluetoothGattCharacteristic bluetoothGattCharacteristic_tx;
    private static BluetoothLeScanner bluetoothLeScanner;
    private static BluetoothDevice bluetoothDevice;

    // 연결 관련 변수
    public static boolean connected = false;

    // 전역변수
    private Context gContext;
    private boolean reliableRequest = false;

    @Nullable
    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return null;
    }

    @Override
    protected boolean isDebug() {
        return BuildConfig.DEBUG;
    }

    public void initializeSensor(Context context) {
        if (gContext == null) {
            gContext = context;
        }
        setBluetoothAdapter(gContext);
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        DfuServiceInitiator.createDfuNotificationChannel(gContext);
        DfuServiceListenerHelper.registerProgressListener(gContext, dfuProgressListener);
    }

    public void setBluetoothAdapter(Context context) {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    public void finishSensor(boolean unregister) {
        stopScan();
        disconnectSensor();

        Handler handler = new Handler();
        handler.postDelayed(() -> {
            if (bluetoothGatt != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGatt.close();
                        bluetoothGatt = null;
                    }
                } else {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            }
        }, 250);

        if (unregister) {
            DfuServiceListenerHelper.unregisterProgressListener(gContext, dfuProgressListener);
        }
    }

    public void startDFUService(File firmware, String deviceAddress) {
        if (firmware.isFile()) {
            final DfuServiceInitiator starter = new DfuServiceInitiator(deviceAddress)
                    .setKeepBond(true)
                    .setDisableNotification(true);

            starter.setPrepareDataObjectDelay(300L);

            Uri mFileStreamUri = Uri.fromFile(firmware);
            starter.setZip(mFileStreamUri, firmware.getPath());
            starter.start(gContext, RLSensor.class);
            //final DfuServiceController controller = starter.start(gContext, RLSensor.class);
        } else {
            Toast.makeText(gContext, "저장된 파일이 없음", Toast.LENGTH_SHORT).show();
            disconnectSensor();
        }
    }

    public void startScan() {
        List<ScanFilter> filterList = new ArrayList<>();
        ScanFilter scanFilter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uuid_service)).build();
        filterList.add(scanFilter);
        ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner.startScan(filterList, scanSettings, scanCallback);
                }
            } else {
                bluetoothLeScanner.startScan(filterList, scanSettings, scanCallback);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopScan() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner.stopScan(scanCallback);
                    bluetoothLeScanner.stopScan(unknownTypeScanCallback);
                }
            } else {
                bluetoothLeScanner.stopScan(scanCallback);
                bluetoothLeScanner.stopScan(unknownTypeScanCallback);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void connectSensor(Context context, String addr) {
        if (gContext == null) {
            gContext = context;
        }
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(addr);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothGatt != null) { // 기존의 bluetoothGatt가 설정되어 있을 때
                    if (!bluetoothGatt.getDevice().getAddress().equals(addr)) { // 기존 bluetoothGatt 설정값의 주소와 연결을 시도하려는 주소가 같지 않을 때
                        bluetoothGatt.close();
                        bluetoothDevice = device;
                        bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
                    } else {
                        bluetoothGatt.connect();
                    }
                } else { // 기존 bluetoothGatt가 설정되어 있지 않을 때
                    if (device.getType() == BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                        List<ScanFilter> filters = new ArrayList<>();
                        ScanFilter scanFilter = new ScanFilter.Builder().setDeviceAddress(addr).build();
                        filters.add(scanFilter);
                        ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
                        if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                            bluetoothLeScanner.startScan(filters, scanSettings, unknownTypeScanCallback);
                        }
                    } else {
                        bluetoothDevice = device;
                        bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
                    }
                }
            }
        } else {
            if (bluetoothGatt != null) { // 기존의 bluetoothGatt가 설정되어 있을 때
                if (!bluetoothGatt.getDevice().getAddress().equals(addr)) { // 기존 bluetoothGatt 설정값의 주소와 연결을 시도하려는 주소가 같지 않을 때
                    bluetoothGatt.close();
                    bluetoothDevice = device;
                    bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    bluetoothGatt.connect();
                }
            } else { // 기존 bluetoothGatt가 설정되어 있지 않을 때
                if (device.getType() == BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                    List<ScanFilter> filters = new ArrayList<>();
                    ScanFilter scanFilter = new ScanFilter.Builder().setDeviceAddress(addr).build();
                    filters.add(scanFilter);
                    ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
                    bluetoothLeScanner.startScan(filters, scanSettings, unknownTypeScanCallback);
                } else {
                    bluetoothDevice = device;
                    bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
                }
            }
        }
    }

    public void disconnectSensor() {
        if (isConnected()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt.disconnect();
                }
            } else {
                bluetoothGatt.disconnect();
            }
        }
    }

    public void dfuConnect(Context context, String addr) {
        if (gContext == null) {
            gContext = context;
        }
        //BluetoothDevice device = bluetoothAdapter.getRemoteDevice(addr);
        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter scanFilter = new ScanFilter.Builder().setDeviceAddress(addr).build();
        filters.add(scanFilter);
        ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                }
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner.startScan(filters, scanSettings, dfuScanCallback);
                }
            }
        } else {
            if (bluetoothGatt != null) {
                bluetoothGatt.close();
            }
            bluetoothLeScanner.startScan(filters, scanSettings, dfuScanCallback);
        }
    }

    public boolean writeCommand(int command) {
        byte[] cmd = {(byte) 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        switch (command) {
            case SENSOR_MSG_SYSTEM_STATUS:
                cmd[2] = 0x01;
                break;
            case SENSOR_MSG_REQUEST_FIRM_VERSION:
                cmd[2] = 0x02;
                break;
            case SENSOR_MSG_REQUEST_FIRM_UPDATE:
                cmd[2] = 0x03;
                break;
            case SENSOR_MSG_REQUEST_REBOOT:
                cmd[2] = 0x04;
                break;
            case SENSOR_MSG_REQUEST_ALL_CALI:
                cmd[2] = 0x10;
                break;
            case SENSOR_MSG_REQUEST_ACCEL_CALI:
                cmd[2] = 0x11;
                break;
            case SENSOR_MSG_REQUEST_GYRO_CALI:
                cmd[2] = 0x12;
                break;
            case SENSOR_MSG_REQUEST_ORIGIN_CALI:
                cmd[2] = 0x14;
                break;
            case SENSOR_MSG_REQUEST_RESET:
                cmd[1] = (byte) 0xFF;
                cmd[2] = (byte) 0xFF;
                break;
            case SENSOR_MSG_IMU:
                cmd[1] = 0x01;
                break;
            case SENSOR_MSG_ATT:
                cmd[1] = 0x01;
                cmd[2] = 0x01;
                break;
            default:
                break;
        }
        boolean requestSuccess = false;
        bluetoothGattCharacteristic_tx.setValue(cmd);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                requestSuccess = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic_tx);
            }
        } else {
            requestSuccess = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic_tx);
        }
        setReliableRequest(true);
        return requestSuccess;
    }

    // 연결 체크 함수
    public boolean isConnected() {
        //bluetoothGatt.getConnectionState(bluetoothDevice);
        return connected;
    }

    public void setConnected(boolean connectState) {
        connected = connectState;
    }

    public boolean isBTEnable() {
        return bluetoothAdapter.isEnabled();
    }

    public String getDeviceName() {
        String name = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                name = bluetoothDevice.getName();
            }
        } else {
            name = bluetoothDevice.getName();
        }
        return name;
    }

    public String getDeviceAddr() {
        return bluetoothDevice.getAddress();
    }

    public boolean notReliableRequest() {
        return !reliableRequest;
    }

    private void setReliableRequest(boolean request) {
        reliableRequest = request;
    }

    private final BluetoothGattCallback dfuGattCallback = new BluetoothGattCallback() {
        final Intent dfuConnectIntent = new Intent("DFU_CONNECT");
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) { // 연결 되었을 때
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.requestMtu(247);
                    }
                } else {
                    gatt.requestMtu(247);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) { // 연결 해제 되었을 때
                dfuConnectIntent.putExtra("DFU_CONNECT_STATE", false);
                LocalBroadcastManager.getInstance(gContext).sendBroadcast(dfuConnectIntent);
            }

        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            dfuConnectIntent.putExtra("DFU_CONNECT_STATE", true);
            LocalBroadcastManager.getInstance(gContext).sendBroadcast(dfuConnectIntent);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
        }
    };

    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        final Intent intent = new Intent("BLE_STATUS");

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) { // 연결 되었을 때
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.requestMtu(247);
                    }
                } else {
                    gatt.requestMtu(247);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) { // 연결 해제 되었을 때
                setConnected(false);
                setReliableRequest(false);
                intent.putExtra("BLE_CONNECTION_STATUS", false);
                LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bluetoothGatt = gatt;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGattCharacteristic_tx = bluetoothGatt.getService(UUID.fromString(uuid_service)).getCharacteristic(UUID.fromString(uuid_character_tx));
                        bluetoothGattCharacteristic_tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                        BluetoothGattCharacteristic bluetoothGattCharacteristic_rx = gatt.getService(UUID.fromString(uuid_service)).getCharacteristic(UUID.fromString(uuid_character_rx));
                        gatt.setCharacteristicNotification(bluetoothGattCharacteristic_rx, true);
                        BluetoothGattDescriptor bluetoothGattDescriptor = bluetoothGattCharacteristic_rx.getDescriptor(UUID.fromString(uuid_descriptor));
                        bluetoothGattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        bluetoothGatt.writeDescriptor(bluetoothGattDescriptor);

                        bluetoothGatt.abortReliableWrite();
                    }
                } else {
                    bluetoothGattCharacteristic_tx = bluetoothGatt.getService(UUID.fromString(uuid_service)).getCharacteristic(UUID.fromString(uuid_character_tx));
                    bluetoothGattCharacteristic_tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    BluetoothGattCharacteristic bluetoothGattCharacteristic_rx = gatt.getService(UUID.fromString(uuid_service)).getCharacteristic(UUID.fromString(uuid_character_rx));
                    gatt.setCharacteristicNotification(bluetoothGattCharacteristic_rx, true);
                    BluetoothGattDescriptor bluetoothGattDescriptor = bluetoothGattCharacteristic_rx.getDescriptor(UUID.fromString(uuid_descriptor));
                    bluetoothGattDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    bluetoothGatt.writeDescriptor(bluetoothGattDescriptor);

                    bluetoothGatt.abortReliableWrite();
                }
                setConnected(true);

                intent.putExtra("BLE_CONNECTION_STATUS", true);
                intent.putExtra("Connected_Address", getDeviceAddr());
                LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.executeReliableWrite();
                }
            } else {
                gatt.executeReliableWrite();
            }
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            super.onReliableWriteCompleted(gatt, status);
            setReliableRequest(false);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            int[] header = new int[8];
            int[] payload = new int[247];

            for (int i = 0; i < 8; i++) {
                header[i] = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, i);
            }

            int message_id = (header[1] << 8) | header[2];
            int message_length = header[3];
            int message_device_id = (header[4] << 24) | (header[5] << 16) | (header[6] << 8) | header[7];

            for (int i = 0; i < message_length; i++) {
                payload[i] = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, i + 8);
            }

            Intent intent = new Intent("BLE_NOTIFY_DATA");
            intent.putExtra("MSG_ID", message_id);
            intent.putExtra("MSG_LENGTH", message_length);
            intent.putExtra("MSG_DEVICE_ID", message_device_id);
            intent.putExtra("MSG_PAYLOAD", payload);
            LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
        }
    };

    private final ScanCallback dfuScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            bluetoothDevice = result.getDevice();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, dfuGattCallback, BluetoothDevice.TRANSPORT_LE);
                }
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner.stopScan(dfuScanCallback);
                }
            } else {
                bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, dfuGattCallback, BluetoothDevice.TRANSPORT_LE);
                bluetoothLeScanner.stopScan(dfuScanCallback);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Toast.makeText(gContext, gContext.getResources().getString(R.string.sensor_re_scan_for_a_while), Toast.LENGTH_LONG).show();
            resetBluetoothAdapter();
        }
    };

    private final ScanCallback unknownTypeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            bluetoothDevice = result.getDevice();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
                }
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner.stopScan(unknownTypeScanCallback);
                }
            } else {
                bluetoothGatt = bluetoothDevice.connectGatt(gContext, true, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
                bluetoothLeScanner.stopScan(unknownTypeScanCallback);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Toast.makeText(gContext, gContext.getResources().getString(R.string.sensor_re_scan_for_a_while), Toast.LENGTH_LONG).show();
            resetBluetoothAdapter();
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        final Intent intentLocalBroadcast = new Intent("BLE_SCAN");

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice bluetoothDevice = result.getDevice();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    String rssi = "" + result.getRssi();
                    String[] deviceInfo = new String[]{bluetoothDevice.getName(), bluetoothDevice.getAddress(), rssi};
                    intentLocalBroadcast.putExtra("ble_device_info", deviceInfo);
                }
            } else {
                String rssi = "" + result.getRssi();
                String[] deviceInfo = new String[]{bluetoothDevice.getName(), bluetoothDevice.getAddress(), rssi};
                intentLocalBroadcast.putExtra("ble_device_info", deviceInfo);
            }

            LocalBroadcastManager.getInstance(gContext).sendBroadcast(intentLocalBroadcast);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Toast.makeText(gContext, gContext.getResources().getString(R.string.sensor_re_scan_for_a_while), Toast.LENGTH_LONG).show();

            resetBluetoothAdapter();
        }
    };

    private void resetBluetoothAdapter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(gContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter.disable();
                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(() -> bluetoothAdapter.enable(),300);
            }
        } else {
            bluetoothAdapter.disable();
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> bluetoothAdapter.enable(),300);
        }
    }

    final DfuProgressListener dfuProgressListener = new DfuProgressListener() {
        @Override
        public void onDeviceConnecting(@NonNull String deviceAddress) {
            Log.e("RLVersionManager", "DFU Process Starting");
        }

        @Override
        public void onDeviceConnected(@NonNull String deviceAddress) {
            Log.e("RLVersionManager", "Device connected");
        }

        @Override
        public void onDfuProcessStarting(@NonNull String deviceAddress) {
        }

        @Override
        public void onDfuProcessStarted(@NonNull String deviceAddress) {
            Log.e("RLVersionManager", "DFU Process Started");
            Intent intent = new Intent("RL_DFU_SERVICE");
            intent.putExtra("DFU_STATUS", "STARTED");
            LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
        }

        @Override
        public void onEnablingDfuMode(@NonNull String deviceAddress) {
            Log.e("RLVersionManager", "DFU mode enabled");

        }

        @Override
        public void onProgressChanged(@NonNull String deviceAddress, int percent, float speed, float avgSpeed, int currentPart, int partsTotal) {
            Intent intent = new Intent("RL_DFU_SERVICE");
            intent.putExtra("DFU_STATUS", "PROCESSING");
            intent.putExtra("DFU_PROCESSING_PERCENT", percent);
            intent.putExtra("DFU_PROCESSING_SPEED", speed);
            intent.putExtra("DFU_PROCESSING_SIZE", partsTotal);
            LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
        }

        @Override
        public void onFirmwareValidating(@NonNull String deviceAddress) {
        }

        @Override
        public void onDeviceDisconnecting(String deviceAddress) {

        }

        @Override
        public void onDeviceDisconnected(@NonNull String deviceAddress) {
            Log.e("RLVersionManager", "Device disconnected");
        }

        @Override
        public void onDfuCompleted(@NonNull String deviceAddress) {
            Log.e("RLVersionManager", "DFU Process completed");
            Intent intent = new Intent("RL_DFU_SERVICE");
            intent.putExtra("DFU_STATUS", "COMPLETE");
            LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
        }

        @Override
        public void onDfuAborted(@NonNull String deviceAddress) {
            Log.e("RLVersionManager", "DFU process aborted");
            Intent intent = new Intent("RL_DFU_SERVICE");
            intent.putExtra("DFU_STATUS", "ABORTED");
            intent.putExtra("ADDRESS", deviceAddress);
            LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
        }

        @Override
        public void onError(@NonNull String deviceAddress, int error, int errorType, String message) {

            Log.e("RLVersionManager", "DFU process error " + errorType);
        }
    };
}