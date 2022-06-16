package com.starpickers.riderloglibrary;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.maps.model.LatLng;

import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RLGeneralService extends Service {

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 클래스
    private static RLSensor rlSensor;
    @SuppressLint("StaticFieldLeak")
    private static RLServerService rlServerService;
    @SuppressLint("StaticFieldLeak")
    private static RLLocationService rlGeometricService;

    // 스케쥴러
    private static ScheduledExecutorService serviceServer;
    private static ScheduledExecutorService servicePreserve;

    // 전역변수
    private static final int[] IMUDATA = new int[6];
    public static final float[] ATTITUDE = new float[2];
    public static float[] GNSS = new float[4];
    private static final int[] AUTOCORRELATION = new int[3];  // log Server api 요청 시 필요
    private static final boolean[] EVENTS = new boolean[4];
    private Context gsContext;
    private String sensorAddress = "DEFAULT_ADDR";
    private SharedPreferences mainPreferences;
    private SharedPreferences userInfoPreference;
    public static final String destAddr = "www.riderlog.net";
    public static final int destPort = 52810;
    private final String b2bMonitoringURL = "https://riderlog-dev.vo3cni3vr4ram.ap-northeast-2.cs.amazonlightsail.com/api/v1/";
    private String firmwareVersion = "";

    // E-Call 관련 변수
    private long lastOccurTime;
    private Handler accidentHandler;

    // firmware params
    public static final int NOT_UPDATED_CORRECTLY = 1;
    public static final int DOWNLOAD_FAILED = 2;
    public static final int FIRMWARE_UPDATE_ABORTED = 3;

    // 서비스 동작 flag
    private static boolean bluetoothEnable;
    private static boolean locationEnable;
    public static boolean serviceInitialized = false;
    public static boolean serviceRunning = false;
    private static boolean driving = false;
    private static boolean firmUpdating = false;
    private static boolean firmUpdateComplete = false;
    private boolean detection;
    private long detectionTime;


    public RLGeneralService() { // get service Objects
        if (rlSensor == null) {
            rlSensor = new RLSensor();
        }
        if (rlServerService == null) {
            rlServerService = new RLServerService();
        }
        if (rlGeometricService == null) {
            rlGeometricService = new RLLocationService();
        }
    }

    public void initializeRLService(Context context) {
        setServiceInitialize(true);
        gsContext = context;

        mainPreferences = gsContext.getSharedPreferences("MAIN_PREFERENCE", Context.MODE_PRIVATE);
        userInfoPreference = gsContext.getSharedPreferences("USER_INFO", Context.MODE_PRIVATE);

        rlSensor.initializeSensor(gsContext);
        rlServerService.initializeServerFeature(gsContext);
        rlGeometricService.initializeLocation(gsContext);

        LocationManager locationManager = (LocationManager) gsContext.getSystemService(Context.LOCATION_SERVICE);
        setLocationEnable(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        setBluetoothEnable(isBTEnable());

        if (isLocationEnable()) {
            startLocationRequestUpdate();
        }

        IntentFilter interiorIntentFilter = new IntentFilter();
        interiorIntentFilter.addAction("BLE_STATUS");
        interiorIntentFilter.addAction("BLE_NOTIFY_DATA");
        interiorIntentFilter.addAction("LOCATION_UPDATE");
        interiorIntentFilter.addAction("ASSIGN_RIDER");
        interiorIntentFilter.addAction("ASSIGN_CHECK");
        interiorIntentFilter.addAction("RELEASE_ASSIGN");
        interiorIntentFilter.addAction("START_DRIVING");
        interiorIntentFilter.addAction("STOP_DRIVING");
        interiorIntentFilter.addAction("ALREADY_DOWNLOADED");
        interiorIntentFilter.addAction("DFU_CONNECT");
        interiorIntentFilter.addAction("RL_DFU_SERVICE");
        interiorIntentFilter.addAction("NOT_OCCUR");
        LocalBroadcastManager.getInstance(gsContext).registerReceiver(interiorBroadcastReceiver, interiorIntentFilter);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 위치 및 블루투스 Broadcast Receiver 등록
        IntentFilter exteriorIntentFilter = new IntentFilter();
        exteriorIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        exteriorIntentFilter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        exteriorIntentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        registerReceiver(exteriorBroadcastReceiver, exteriorIntentFilter);
    }

    /*
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification mNotification = createNotification();
        startForeground(1, mNotification);

        Intent startService = new Intent("SERVICE_STARTED");
        LocalBroadcastManager.getInstance(gsContext).sendBroadcast(startService);

        return super.onStartCommand(intent, flags, startId);
    }

    private Notification createNotification() {
        Context notificationContext = setLocale(this);
        // app 실행 intent 생성이 안됨, Library에 rider_log app module 추가해야만 함

        NotificationChannel channel = new NotificationChannel("rlService", "RLService", NotificationManager.IMPORTANCE_HIGH);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
        Notification.Builder builder = new Notification.Builder(notificationContext, "rlService")
                .setSmallIcon(R.drawable.ic_logo)
                .setContentTitle(notificationContext.getString(R.string.running_service_title))
                .setContentText(notificationContext.getString(R.string.running_service_message))
                .setAutoCancel(true);
        return builder.build();
    }
    */

    protected Context setLocale(Context context) {
        String language = getApplicationContext().getSharedPreferences("MAIN_PREFERENCE", Context.MODE_PRIVATE).getString("SELECTED_LANGUAGE", "ko");
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Configuration configuration = context.getResources().getConfiguration();
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);

        return context.createConfigurationContext(configuration);
    }


    private void renewalContext(Context context) {
        if (context != null) {
            gsContext = context;
        } else {
            gsContext = getApplicationContext();
        }
    }

    public void getFirebaseRemoteConfig(Activity activity, boolean debug) {
        rlServerService.getRemoteConfig(activity, debug);
    }

    public void autoConnect() {
        if (!getConnectionStatus()) { // when sensor is not connect
            SharedPreferences userInfoPreference = gsContext.getSharedPreferences("USER_INFO", Context.MODE_PRIVATE);
            String savedAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
            if (!savedAddress.equals("")) {
                connectSensor(gsContext, savedAddress);
            }
        }
    }

    private void dfuModeConnect(Context context) {
        sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
        if (!sensorAddress.equals("")) {
            // Mac Address 끝자리 +1 해서 연결 해야함
            char lastCharacter = sensorAddress.charAt(sensorAddress.length() - 1);
            char beforeLastCharacter = sensorAddress.charAt(sensorAddress.length() - 2);
            switch (lastCharacter) {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case 'A':
                case 'B':
                case 'C':
                case 'D':
                case 'E':
                    lastCharacter = (char) (lastCharacter + 1);
                    break;
                case '9':
                    lastCharacter = 'A';
                    break;
                case 'F':
                    beforeLastCharacter = (char) (beforeLastCharacter + 1);
                    lastCharacter = '0';
                    break;
            }
            String dfuModeAddress = sensorAddress.substring(0, sensorAddress.lastIndexOf(":") + 1) + beforeLastCharacter + lastCharacter;
            rlSensor.dfuConnect(context, dfuModeAddress);
        }
    }

    // 위치정보 업데이트 요청
    public void startLocationRequestUpdate() {
        if (rlGeometricService != null) {
            //rlGeometricService.resetAccDistance();
            rlGeometricService.requestUpdate();
        }
    }

    public void startRLServices() {
        setServiceRunning(true);

        // 서버 서비스 시작
        if (serviceServer == null) {
            serviceServer = Executors.newSingleThreadScheduledExecutor();
            serviceServer.scheduleAtFixedRate(runnableServer, 0, 500, TimeUnit.MILLISECONDS);
        }

        // 자체 서버 전송 서비스 시작
        if (servicePreserve == null) {
            servicePreserve = Executors.newSingleThreadScheduledExecutor();
            servicePreserve.scheduleAtFixedRate(runnablePreserve, 0, 50, TimeUnit.MILLISECONDS);
        }
        Handler handler = new Handler();
        handler.postDelayed(this::startDrivingEvent, 500);
    }

    public void stopRLServices() {
        setServiceRunning(false);
        stopDrivingEvent();

        // 센서 서비스 중지 - 이 작업이 과연 필요한가? 센서 중단 전에 굳이 메시지 전송 중단 기능이 있어야하나?
        if (getConnectionStatus()) {
            stopSensorSequence();
        }

        // 서버 쪽 서비스 중지
        if (serviceServer != null) {
            serviceServer.shutdown();
            serviceServer = null;
        }

        if (servicePreserve != null) {
            servicePreserve.shutdown();
            servicePreserve = null;
        }
    }

    public void finishRLService() {
        if (rlGeometricService != null) {
            rlGeometricService.stopUpdate();
        }

        if (rlServerService != null) {
            rlServerService.closeServerFeature();
        }

        if (rlSensor != null) {
            rlSensor.finishSensor(true);
            setNotConnected();
        }
    }

    private void startSensorSequence() {
        Thread initRequest = new Thread(() -> {
            while (true) {
                if (rlSensor.writeCommand(RLSensor.SENSOR_MSG_IMU)) {
                    break;
                }
            }
        });
        initRequest.start();
    }

    public void stopSensorSequence() {
        Thread finishRequest = new Thread(() -> {
            while (true) {
                if (rlSensor.writeCommand(RLSensor.SENSOR_MSG_ATT)) {
                    break;
                }
            }
        });
        finishRequest.start();
    }

    private void startDrivingEvent() {
        sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
        String removeColon = sensorAddress.replace(":", "");

        if (!isDriving()) {
            rlServerService.startDriving(gsContext, b2bMonitoringURL, removeColon);
        }
    }

    private void stopDrivingEvent() {
        sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
        String removeColon = sensorAddress.replace(":", "");
        int drivingID = mainPreferences.getInt("DRIVING_ID", -1);

        if (isDriving()) {
            rlServerService.stopDriving(gsContext, b2bMonitoringURL, removeColon, drivingID);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("Service Test", "Destroy service");
        setServiceInitialize(false);

        Intent finishRoutineDone = new Intent("SERVICE_FINISHED");
        LocalBroadcastManager.getInstance(gsContext).sendBroadcast(finishRoutineDone);

        LocalBroadcastManager.getInstance(gsContext).unregisterReceiver(interiorBroadcastReceiver);
        unregisterReceiver(exteriorBroadcastReceiver);
    }

    public void setServiceInitialize(boolean tof) {
        serviceInitialized = tof;
    }

    public boolean isServiceInitialize() {
        return serviceInitialized;
    }

    private void setServiceRunning(boolean tof) {
        serviceRunning = tof;
    }

    public boolean isServiceRunning() {
        return serviceRunning;
    }

    private boolean isDriving() {
        return driving;
    }

    private void nowDriving(boolean _driving) {
        driving = _driving;
    }

    // Scan Functions
    public void scanStart() {
        // 블루투스 장치 켰는지 확인
        if (rlSensor != null) {
            rlSensor.startScan();
        }
    }
    public void scanStop() {
        if (rlSensor != null) {
            rlSensor.stopScan();
        }
    }

    // Connection Functions
    public void connectSensor(Context context, String addr) {
        addr = addr.toUpperCase();
        if (rlSensor != null) {
            rlSensor.connectSensor(context, addr);
        }
    }

    public void disconnectSensor(boolean release) {
        if (release) { // 사용자가 직접 다른 센서를 선택하려고 할 때
            releaseAssign();
        }
        if (rlSensor != null) {
            rlSensor.disconnectSensor();
        }
    }

    public void finishSensorAndReboot() {
        requestReboot();
        Handler handler = new Handler();
        handler.postDelayed(() -> rlSensor.finishSensor(false), 500);
    }

    public void checkAssignStatus(String phoneNum) {
        rlServerService.assignCheck(gsContext, b2bMonitoringURL, phoneNum);
    }

    public void requestAssignUser(Context context, String phone, String sensor) {
        rlServerService.riderSensorAssign(context, b2bMonitoringURL, phone, sensor);
    }

    public void releaseAssign() {
        String phoneNum = userInfoPreference.getString("PHONE", "");
        String assignedAddress = userInfoPreference.getString("ASSIGNED_ADDRESS", "");
        if (!phoneNum.equals("") && !assignedAddress.equals("")) {
            rlServerService.releaseAssign(gsContext, b2bMonitoringURL, phoneNum, assignedAddress);
            //stopDrivingEvent();
        }
    }

    private boolean compareFirmwareVersion() {
        String remoteConfigFirmwareVersion = mainPreferences.getString("RC_FIRM_VERSION", "");
        boolean equals = true;
        if (!remoteConfigFirmwareVersion.equals("")) {
            /* 펌웨어 버전 체크
            Split으로 Major Minor patch 세 부분 각각 비교
            if (remoteConfigFirmwareVersion.equals(getFirmwareVersion())) {
                equals = true;
            } else {
                String[] currentConnectedDeviceFirmwareVersionParse = getFirmwareVersion().split("\\.");
                String[] remoteConfigFirmwareVersionParse = remoteConfigFirmwareVersion.split("\\.");

            }
            */
            equals = remoteConfigFirmwareVersion.equals(getFirmwareVersion());
        }
        return equals;
    }

    //flag 관련 메소드
    public boolean getConnectionStatus() {
        return rlSensor.isConnected();
    }

    private void setNotConnected(){
        if (getConnectionStatus()) {
            rlSensor.setConnected(false);
        }
    }

    public void setFirmwareVersion(int[] firmVer) {
        firmwareVersion = firmVer[0] + "." + firmVer[1] + "." + firmVer[2];
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public double getAccumulatedDistance() {
        return rlGeometricService.getAccDistance();
    }

    public boolean isBTEnable() {
        return rlSensor.isBTEnable();
    }

    public long getLastOccurTime() {
        return lastOccurTime;
    }

    private void setLastOccurTime(long curTime) {
        lastOccurTime = curTime;
    }

    public void setBluetoothEnable(boolean tof) {
        bluetoothEnable = tof;
    }

    public boolean isBluetoothEnable() {
        return bluetoothEnable;
    }

    public void setLocationEnable(boolean tof) {
        locationEnable = tof;
    }

    public boolean isLocationEnable() {
        return locationEnable;
    }

    private void setFirmUpdating(boolean requested) {
        firmUpdating = requested;
    }

    public boolean isFirmUpdating() {
        return firmUpdating;
    }

    private void setFirmUpdateComplete() {
        firmUpdateComplete = true;
    }

    private boolean isFirmUpdateComplete() {
        return firmUpdateComplete;
    }

    //Server 관련 메소드
    private boolean isTCPAvailable() {
        return rlServerService.isTcpAvailable();
    }

    public boolean isNetworkAvailable() {
        return rlServerService.isNetworkAvailable();
    }

    public boolean getAssignStatus(Context context) {
        if (mainPreferences == null) {
            if (gsContext == null) {
                renewalContext(context);
            }
            mainPreferences = gsContext.getSharedPreferences("MAIN_PREFERENCE", Context.MODE_PRIVATE);
        }
        return mainPreferences.getBoolean("ASSIGNED", false);
    }

    private void setTryTCPConnection() {
        rlServerService.setTryTCPConnection(true);
    }

    private boolean isTryTCPConnection() {
        return rlServerService.isTryTCPConnection();
    }

    // 데이터 리턴
    public void requestFirmVersion() {
        rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_FIRM_VERSION);
    }

    public boolean requestFirmUpdate() {
        return rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_FIRM_UPDATE);
    }

    public boolean requestSetOrigin() {
        return rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_ORIGIN_CALI);
    }

    public boolean requestReboot() {
        return rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_REBOOT);
    }

    /*
    public boolean requestCalibrateAll() {
        return rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_ALL_CALI);
    }

    public boolean requestCalibrateAcc() {
        return rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_ACCEL_CALI);
    }

    public boolean requestCalibrateGyro() {
        return rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_GYRO_CALI);
    }

    public boolean requestFactoryReset() {
        return rlSensor.writeCommand(RLSensor.SENSOR_MSG_REQUEST_RESET);
    }
    */

    private final Runnable runnableServer = new Runnable() {
        @Override
        public void run() {
            if (isNetworkAvailable()) {
                String status = mainPreferences.getString("STATUS", "stopped");
                int managerID = mainPreferences.getInt("MANAGER_ID", 0);
                int drivingID = mainPreferences.getInt("DRIVING_ID", -1);
                sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "DEFAULT_ADDR");
                if (!sensorAddress.equals("")) {
                    String removeColon = sensorAddress.replace(":", "");
                    rlServerService.writeDB(removeColon, IMUDATA, ATTITUDE, GNSS, AUTOCORRELATION, EVENTS, status, managerID, drivingID);
                }
            }
        }
    };

    private final Runnable runnablePreserve = new Runnable() {
        @Override
        public void run() {
            if (isTCPAvailable()) {
                sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "DEFAULT_ADDR");
                String phoneNum = userInfoPreference.getString("PHONE", "");
                if (!sensorAddress.equals("")) {
                    String removeColon = sensorAddress.replace(":", "");
                    rlServerService.writeFile(removeColon, phoneNum, IMUDATA, AUTOCORRELATION, ATTITUDE, GNSS, EVENTS);
                }
            } else {
                if (rlServerService.isNetworkAvailable()) {
                    if (!isTryTCPConnection()) {
                        rlServerService.initTCP(destAddr, destPort);
                        setTryTCPConnection();
                    }
                }
            }
        }
    };

    private final BroadcastReceiver exteriorBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            switch (action) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (BluetoothAdapter.STATE_TURNING_OFF == state) {
                        setBluetoothEnable(false);
                        rlSensor.finishSensor(false);
                    } else if (BluetoothAdapter.STATE_ON == state) {
                        setBluetoothEnable(true);
                        renewalContext(context);
                        rlSensor.setBluetoothAdapter(gsContext);
                        autoConnect();
                    }
                    break;
                case LocationManager.PROVIDERS_CHANGED_ACTION:
                    LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                    boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                    boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

                    if (!isGpsEnabled && !isNetworkEnabled) {
                        setLocationEnable(false);
                        rlGeometricService.stopUpdate();
                    } else {
                        setLocationEnable(true);
                        rlGeometricService.requestUpdate();
                    }
                    break;
                case DownloadManager.ACTION_DOWNLOAD_COMPLETE:
                    long reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    long downloadQueueId = rlServerService.getDownloadQueueId();

                    boolean isFailed = false;
                    if (reference == downloadQueueId) {
                        DownloadManager.Query query = new DownloadManager.Query();  // 다운로드 항목 조회에 필요한 정보 포함
                        query.setFilterById(reference);

                        DownloadManager mDownloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                        Cursor cursor = mDownloadManager.query(query);

                        cursor.moveToFirst();

                        int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int columnReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        int status = cursor.getInt(columnIndex);
                        int reason = cursor.getInt(columnReason);
                        cursor.close();

                        switch (status) {
                            case DownloadManager.STATUS_SUCCESSFUL :
                                Log.d("Download Success", "complete download" );
                                firmwareUpdateRequest();
                                break;
                            case DownloadManager.STATUS_PAUSED :
                                isFailed = true;
                                Toast.makeText(context, "다운로드가 중단되었습니다\n원인 - "+reason, Toast.LENGTH_SHORT).show();
                                Log.d("Download Paused", "Reason - " + reason);
                                break;
                            case DownloadManager.STATUS_FAILED :
                                isFailed = true;
                                // 서비스 종료 후 다시 시도, 서버 동작하지 않음, destination file 접근 불가(or 저장공간 부족)
                                Toast.makeText(context, "다운로드를 실패했습니다\n원인 - "+reason, Toast.LENGTH_SHORT).show();
                                Log.d("Download Failed", "Reason - " + reason);
                                break;
                        }

                        if (isFailed) {
                            Intent alertFirmwareIssue = new Intent("Firmware_Issue");
                            alertFirmwareIssue.putExtra("ISSUE_STATUS", DOWNLOAD_FAILED);
                            LocalBroadcastManager.getInstance(gsContext).sendBroadcast(alertFirmwareIssue);
                        }
                    }
                    break;
            }
        }
    };

    public final BroadcastReceiver interiorBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Handler handler = new Handler();
            switch (intent.getAction()) {
                case "BLE_STATUS":
                    if (intent.getExtras().getBoolean("BLE_CONNECTION_STATUS")) {
                        Log.e("INFO", "CONNECTED");
                        scanStop();
                        if (isFirmUpdateComplete()) {
                            Intent firmwareUpdateIntent = new Intent("FIRM_UPDATE");
                            firmwareUpdateIntent.putExtra("UPDATING", false);
                            LocalBroadcastManager.getInstance(context).sendBroadcast(firmwareUpdateIntent);
                        }

                        sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
                        SharedPreferences.Editor userInfoEditor = userInfoPreference.edit();
                        String connectedAddress = intent.getExtras().getString("Connected_Address");
                        if (sensorAddress.equals("") || !sensorAddress.equals(connectedAddress)) {
                            userInfoEditor.putString("CURRENT_CONNECTED", connectedAddress);
                            userInfoEditor.apply();
                        }

                        startDrivingEvent();

                        handler.postDelayed(() -> {
                            if (rlSensor.notReliableRequest()) {
                                Log.d("Firmware Test", "request firmware to Sensor");
                                requestFirmVersion();
                            }
                        }, 250);
                    } else {
                        Log.e("INFO", "DISCONNECTED");
                        // 연결 해제 시 센서로부터 수신받은 배열들 모두 초기화
                        Arrays.fill(IMUDATA, 0);
                        Arrays.fill(ATTITUDE, 0);
                        Arrays.fill(AUTOCORRELATION, 0);
                        Arrays.fill(EVENTS, false);
                        if (isFirmUpdating()) {
                            handler.postDelayed(() -> dfuModeConnect(context), 750);
                        }
                    }
                    break;
                case "ALREADY_DOWNLOADED":
                    firmwareUpdateRequest();
                    break;
                case "DFU_CONNECT":
                    if (intent.getExtras().getBoolean("DFU_CONNECT_STATE")) {
                        if (isFirmUpdating()) {
                            tryTheFirmwareUpdate();
                        } else {
                            requestReboot();
                        }
                    }
                    break;
                case "RL_DFU_SERVICE":
                    switch(intent.getExtras().getString("DFU_STATUS")){
                        case "STARTED":
                            Log.w("DFU", "Transfer started");
                            break;
                        case "PROCESSING":
                            Log.w("DFU", "Processing speed : "
                                    + intent.getExtras().getFloat("DFU_PROCESSING_SPEED") + "KB/s, "
                                    + intent.getExtras().getInt("DFU_PROCESSING_SIZE") + "KB, "
                                    + intent.getExtras().getInt("DFU_PROCESSING_PERCENT") + "%");
                            break;
                        case "COMPLETE":
                            setFirmUpdating(false);
                            handler.postDelayed(() -> {
                                setFirmUpdateComplete();

                                sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
                                connectSensor(context, sensorAddress);

                                SharedPreferences.Editor userInfoEditor = userInfoPreference.edit();
                                userInfoEditor.putString("UPDATED_SENSOR", sensorAddress);
                                userInfoEditor.apply();
                            }, 1000);
                            break;
                        case "ABORTED":
                            Log.w("DFU", "Transfer aborted");
                            Intent alertFirmwareIssue = new Intent("Firmware_Issue");
                            alertFirmwareIssue.putExtra("ISSUE_STATUS", FIRMWARE_UPDATE_ABORTED);
                            LocalBroadcastManager.getInstance(gsContext).sendBroadcast(alertFirmwareIssue);
                            // 펌웨어 업데이트 aborted -> 재시작 or 연결 종료
                            break;
                        default:
                            break;
                    }
                    break;
                case "BLE_NOTIFY_DATA":
                    switch (intent.getExtras().getInt("MSG_ID")) {
                        case RLSensor.SENSOR_MSG_CHECK_CONNECTION:
                            /* dummy sequence
                            if (rlSensor.notReliableRequest()) {
                                requestFirmVersion();
                            } else {
                                sensorQueue.add(RLSensor.SENSOR_MSG_REQUEST_FIRM_VERSION);
                            }
                            */
                            break;
                        case RLSensor.SENSOR_MSG_REQUEST_FIRM_VERSION:
                            Log.d("Firmware Test", "response firmware from Sensor");
                            int[] firm_ver = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            Intent firmIntent = new Intent("FIRM_VER");
                            firmIntent.putExtra("firm_versions", firm_ver);
                            LocalBroadcastManager.getInstance(context).sendBroadcast(firmIntent);

                            setFirmwareVersion(firm_ver);
                            if (compareFirmwareVersion()) {
                                startSensorSequence();
                            } else {
                                Intent firmwareUpdateIntent = new Intent("FIRM_UPDATE");
                                firmwareUpdateIntent.putExtra("UPDATING", true);
                                LocalBroadcastManager.getInstance(context).sendBroadcast(firmwareUpdateIntent);
                                boolean fileAccessPermissionCheck = false;
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                                            && ContextCompat.checkSelfPermission(context.getApplicationContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                        fileAccessPermissionCheck = true;
                                    }
                                } else {
                                    if (ContextCompat.checkSelfPermission(context.getApplicationContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                        fileAccessPermissionCheck = true;
                                    }
                                }

                                if (fileAccessPermissionCheck) {
                                    firmDownloadSequence();
                                } else {
                                    // 파일 엑세스 권한 없으므로 권한 요청하기
                                    Intent fileAccessRequestIntent = new Intent("NEED_FILE_PERMISSION");
                                    LocalBroadcastManager.getInstance(context).sendBroadcast(fileAccessRequestIntent);
                                }
                            }
                            //startSensorSequence();
                            break;
                        case RLSensor.SENSOR_MSG_IMU:
                            int[] imudata = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            for(int i=0;i<6;i++){
                                IMUDATA[i] = (imudata[4*i] << 24) | (imudata[4*i+1] << 16) | (imudata[4*i+2] << 8) | imudata[4*i+3];
                            }
                            break;
                        case RLSensor.SENSOR_MSG_ATT:
                            int[] attdata = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            for(int i=0;i<2;i++){
                                ATTITUDE[i] = (float)((attdata[4*i] << 24) | (attdata[4*i+1] << 16) | (attdata[4*i+2] << 8) | attdata[4*i+3]);
                            }
                            break;
                        case RLSensor.SENSOR_MSG_GNSS:
                            //노면분석용 알고리즘 데이터
                            int[] calculatedAccData = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            for (int i = 0; i < 3; i++) {
                                AUTOCORRELATION[i] = (calculatedAccData[4 * i] << 24) | (calculatedAccData[4 * i + 1] << 16) | (calculatedAccData[4 * i + 2] << 8) | calculatedAccData[4 * i + 3];
                            }
                            break;
                            /*
                        case RLSensor.SENSOR_MSG_REQUEST_ALL_CALI:
                            int[] all_cali = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            if(all_cali[0] == 0x01){
                                // Calibration Succeeded
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_all_cali_success), Toast.LENGTH_SHORT).show();
                            }else if(all_cali[0] == 0x02){
                                // Calibration failed
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_all_cali_fail), Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case RLSensor.SENSOR_MSG_REQUEST_ACCEL_CALI:
                            int[] acc_cali = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            if(acc_cali[0] == 0xFF){
                                // ACC Calibration failed
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_acc_cali_fail), Toast.LENGTH_SHORT).show();
                            }else if(acc_cali[0] == 0xFE){
                                // ACC Calibration Succeeded
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_acc_cali_success), Toast.LENGTH_SHORT).show();
                            }else{
                                // ACC Calibration try
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_acc_cali_try) + acc_cali[0], Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case RLSensor.SENSOR_MSG_REQUEST_GYRO_CALI:
                            int[] gyro_cali = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            if(gyro_cali[0] == 0xFF){
                                // Gyro Calibration failed
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_gyro_cali_fail), Toast.LENGTH_SHORT).show();
                            }else if(gyro_cali[0] == 0xFE){
                                // Gyro Calibration Succeeded
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_gyro_cali_success), Toast.LENGTH_SHORT).show();
                            }else{
                                // Gyro Calibration try
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_gyro_cali_try) + gyro_cali[0], Toast.LENGTH_SHORT).show();
                            }
                            break;
                            */
                        case RLSensor.SENSOR_MSG_REQUEST_ORIGIN_CALI:
                            int[] origin_cali = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            if(origin_cali[0] == 0x01){
                                // Origin Calibration Succeeded
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_origin_cali_success), Toast.LENGTH_SHORT).show();
                            }else{
                                // Origin Calibration Failed
                                Toast.makeText(context, gsContext.getString(R.string.response_from_sensor_origin_cali_fail), Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case RLSensor.SENSOR_MSG_EVNT:
                            int[] events = intent.getExtras().getIntArray("MSG_PAYLOAD");
                            // 충돌감지
                            EVENTS[0] = events[0] == 0x01;
                            // 전복감지
                            EVENTS[1] = events[1] == 0x01;
                            // 급가속
                            EVENTS[2] = events[2] == 0x01;
                            // 급감속
                            EVENTS[3] = events[3] == 0x01;

                            if(EVENTS[0] || EVENTS[1]){
                                if (!detection) {
                                    detectionTime = System.currentTimeMillis();
                                    detection = true;
                                }
                                // E-Call 기능
                                boolean canSendSMS = mainPreferences.getBoolean("eCall", false);

                                if (System.currentTimeMillis() - detectionTime > 1500) {
                                    if (Settings.canDrawOverlays(context) && canSendSMS) {
                                        e_Call();
                                    }
                                    detection = false;
                                }
                            } else {
                                detection = false;
                            }
                            break;
                        default:
                            break;
                    }
                    break;
                case "LOCATION_UPDATE":
                    GNSS = intent.getExtras().getFloatArray("location_data");
                    break;
                case "NOT_OCCUR":
                    accidentHandler.removeCallbacksAndMessages(null);
                    break;
                case "ASSIGN_RIDER":
                    int assignResponseCode = intent.getExtras().getInt("response_code");
                    if (assignResponseCode == HttpURLConnection.HTTP_OK) {
                        String assignResult = intent.getExtras().getString("ASSIGN_RESULT");
                        SharedPreferences.Editor mainEditor = mainPreferences.edit();
                        SharedPreferences.Editor userInfoEditor = userInfoPreference.edit();
                        if (assignResult.equals("SUCCESS")) {
                            mainEditor.putBoolean("ASSIGNED", true);
                            mainEditor.putString("STATUS", "stopped");
                            mainEditor.putInt("MANAGER_ID", intent.getExtras().getInt("MANAGER_ID"));
                            userInfoEditor.putString("ASSIGNED_ADDRESS", intent.getExtras().getString("ASSIGNED_SENSOR"));
                        } else {
                            mainEditor.putBoolean("ASSIGNED", false);
                        }
                        mainEditor.apply();
                        userInfoEditor.apply();
                    }
                    break;
                case "ASSIGN_CHECK":
                    int assignCheckResponseCode = intent.getExtras().getInt("response_code");
                    if (assignCheckResponseCode == HttpURLConnection.HTTP_OK) {
                        String assignCheckStatus = intent.getExtras().getString("ASSIGN_CHECK_RESULT");

                        SharedPreferences.Editor mainEditor = mainPreferences.edit();
                        SharedPreferences.Editor userInfo = userInfoPreference.edit();
                        if (assignCheckStatus.equals("SUCCESS")) {
                            mainEditor.putBoolean("ASSIGNED", true);
                            String sensorAddress = intent.getExtras().getString("SENSOR");
                            userInfo.putString("ASSIGNED_ADDRESS", sensorAddress);
                            userInfo.putString("VEHICLE", intent.getExtras().getString("VEHICLE"));
                            mainEditor.putInt("MANAGER_ID", intent.getExtras().getInt("MANAGER_ID"));
                        } else {
                            mainEditor.putBoolean("ASSIGNED", false);
                        }
                        mainEditor.apply();
                        userInfo.apply();
                    }
                    break;
                case "RELEASE_ASSIGN":
                    int assignReleaseResponseCode = intent.getExtras().getInt("response_code");
                    if (assignReleaseResponseCode == HttpURLConnection.HTTP_OK) {
                        boolean isReleased = false;
                        String releaseStatus = intent.getExtras().getString("RELEASE_ASSIGN_RESULT");
                        SharedPreferences.Editor mainEditor = mainPreferences.edit();

                        if (releaseStatus.equals("SUCCESS")) {
                            mainEditor.putBoolean("ASSIGNED", false);
                            isReleased = true;
                        } else {
                            String statusCode = intent.getExtras().getString("CODE");
                            // 00 : 등록된 라이더가 아님, 01 : 연결된 센서없거나 센서아이디 틀림, 02 : 차량에 설치된 센서가 아님
                            if (statusCode.equals("01")) {
                                mainEditor.putBoolean("ASSIGNED", false);
                                isReleased = true;
                            } else { // 00 || 02 일 때는 혹시 assign 상태가 true라면 프리퍼런스 변경
                                if (mainPreferences.getBoolean("ASSIGNED", false)) {
                                    mainEditor.putBoolean("ASSIGNED", false);
                                    isReleased = true;
                                }
                            }
                        }
                        mainEditor.apply();

                        if (isReleased) {
                            stopDrivingEvent();
                            SharedPreferences.Editor userInfoEditor = userInfoPreference.edit();
                            userInfoEditor.putString("CURRENT_CONNECTED", "");
                            userInfoEditor.putString("ASSIGNED_ADDRESS", "");
                            userInfoEditor.apply();
                        }
                    }
                    break;
                case "START_DRIVING":
                    if (intent.getExtras().getInt("RESPONSE_CODE") == HttpURLConnection.HTTP_OK) {
                        String status = intent.getExtras().getString("DRIVING_STATUS");
                        int drivingID = intent.getExtras().getInt("DRIVING_ID");

                        if (status.equals("driving")) {
                            nowDriving(true);
                        }

                        SharedPreferences.Editor editor = mainPreferences.edit();
                        editor.putString("STATUS", status);
                        editor.putInt("DRIVING_ID", drivingID);
                        editor.apply();
                    }
                    break;
                case "STOP_DRIVING":
                    if (intent.getExtras().getInt("RESPONSE_CODE") == HttpURLConnection.HTTP_OK) {
                        String status = intent.getExtras().getString("DRIVING_STATUS");

                        if (status.equals("stopped")) {
                            nowDriving(false);
                        }

                        SharedPreferences.Editor editor = mainPreferences.edit();
                        editor.putString("STATUS", status);
                        editor.apply();

                        int managerID = mainPreferences.getInt("MANAGER_ID", 0);
                        int drivingID = mainPreferences.getInt("DRIVING_ID", -1);
                        sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
                        if (!sensorAddress.equals("")) {
                            String removeColon = sensorAddress.replace(":", "");
                            rlServerService.writeDB(removeColon, IMUDATA, ATTITUDE, GNSS, AUTOCORRELATION,  EVENTS, status, managerID, drivingID);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private String addColon(String address) {
        StringBuilder addColonAddress = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            String sub = address.substring(2 * i, 2 * (i + 1));
            addColonAddress.append(sub).append(":");
        }
        addColonAddress.append(address.substring(10));
        return addColonAddress.toString();
    }

    public void firmDownloadSequence() {
        if (isFirmUpdateComplete()) {
            String updatedSensor = userInfoPreference.getString("UPDATED_SENSOR", "");
            String toUpdateSensor = userInfoPreference.getString("CURRENT_CONNECTED", "");
            if (updatedSensor.equals(toUpdateSensor)) {
                // 이미 이전에 업데이트 완료된 센서가 다시 펌웨어 버전체크에서 다르다면
                // 다운로드 받았던 펌웨어 삭제, 앱 껐다 다시 키기 권장, 최소 서비스 중지 이후 다시 시작 필수
                // 같은 상태 반복되면 문의해달라고 알림
                Intent alertFirmwareIssue = new Intent("Firmware_Issue");
                alertFirmwareIssue.putExtra("ISSUE_STATUS", NOT_UPDATED_CORRECTLY);
                LocalBroadcastManager.getInstance(gsContext).sendBroadcast(alertFirmwareIssue);
            } else {
                firmwareUpdateRequest();
            }
        } else {
            downloadFirmware();
        }
    }

    public void downloadFirmware(){
        String firmwareDownloadLink = mainPreferences.getString("RC_FIRM_LINK", "");
        rlServerService.getLatestFirmware(gsContext, firmwareDownloadLink);
    }

    private void firmwareUpdateRequest() {
        setFirmUpdating(true);
        Thread firmwareUpdateThread = new Thread(() -> {
            while (true) {
                if (requestFirmUpdate()) {
                    break;
                }
            }
        });
        firmwareUpdateThread.start();
    }

    public void tryTheFirmwareUpdate() {
        rlSensor.startDFUService(rlServerService.getFirmwareFile(), rlSensor.getDeviceAddr());
    }

    private void e_Call(){
        long curTime = System.currentTimeMillis();
        if ((curTime - getLastOccurTime() > 90 * 1000)) {
            // E-Call 발생 관련 변수 저장
            setLastOccurTime(curTime);
            LatLng eventLocation = new LatLng(GNSS[0], GNSS[1]);

            accidentHandler = new Handler();
            accidentHandler.postDelayed(() -> {
                // Firestore에 기록하는 메소드
                String randomUUID = UUID.randomUUID().toString();
                randomUUID = randomUUID.replace("-", "").substring(0, 20);
                int managerID = mainPreferences.getInt("MANAGER_ID", 0);
                String phoneNum = userInfoPreference.getString("PHONE", "");
                String ecallNum = userInfoPreference.getString("ECallPhone", "");
                if (ecallNum.equals("")) {
                    ecallNum = "01074841500";
                } else {
                    ecallNum += ",01074841500";
                }
                sensorAddress = userInfoPreference.getString("CURRENT_CONNECTED", "");
                String removeColon = sensorAddress.replace(":", "");
                String message = "사고 감지";
                String detailMSG = "[" + message + "] 라이더 번호 " + phoneNum + " 주행 중 사고를 감지했습니다!";
                rlServerService.writeECall(randomUUID, managerID, removeColon, ecallNum, message, detailMSG, false, false, eventLocation);
            }, 1000 * 90);

            // 사용자 응답 받는 Activity 생성
            Intent popup = new Intent(gsContext, ECallActivity.class);
            popup.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            gsContext.startActivity(popup);
        }
    }
}