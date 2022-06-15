package com.starpickers.riderloglibrary;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.firebase.geofire.GeoFireUtils;
import com.firebase.geofire.GeoLocation;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public class RLServerService {
    // Google Firebase 설정
    private final FirebaseFirestore firebaseFirestore;
    private static Socket tcpSocket;
    private static OutputStream outputStream;

    private ConnectivityManager cm;
    private Context serverContext;

    private boolean tcpAvailable = false;
    private boolean networkAvailable = false;
    public boolean tryTCPConnection = false;
    private static boolean NETWORK_AVAILABLE_CELLULAR = false;
    private static boolean NETWORK_AVAILABLE_WIFI = false;
    private long mDownloadQueueId;
    private static File firmware;
    private static final long NUM_BYTES_NEEDED_FOR_FIRMWARE = 1024 * 1024;

    public RLServerService() {
        firebaseFirestore = FirebaseFirestore.getInstance();
    }

    public void initializeServerFeature(Context context) {
        serverContext = context;
        cm = (ConnectivityManager) serverContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerDefaultNetworkCallback(networkCallback);
    }

    public void getRemoteConfig(Activity checkActivity, boolean debug) {
        final Intent appVersionCheckIntent = new Intent("APP_VER");
        final FirebaseRemoteConfig firebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
        FirebaseRemoteConfigSettings mConfigSettings;
        if (debug) {
            mConfigSettings = new FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(60).build();
        } else {
            mConfigSettings = new FirebaseRemoteConfigSettings.Builder().build();
        }

        firebaseRemoteConfig.setConfigSettingsAsync(mConfigSettings);
        //firebaseRemoteConfig.setDefaultsAsync(R.xml.remote_config_default);

        firebaseRemoteConfig.fetchAndActivate().addOnCompleteListener(checkActivity, task -> {
            String installedAppVersion;
            String remoteConfigVersion = "";
            boolean isEqualAppVersion = false;

            if (task.isSuccessful()) {
                // get firmware version info
                SharedPreferences mainPreferences = serverContext.getSharedPreferences("MAIN_PREFERENCE", Context.MODE_PRIVATE);
                SharedPreferences.Editor mainEditor = mainPreferences.edit();
                if (firebaseRemoteConfig.getString("Firm_Version").length() > 0) {
                    mainEditor.putString("RC_FIRM_VERSION", firebaseRemoteConfig.getString("Firm_Version"));
                }
                if (firebaseRemoteConfig.getString("Firm_Download_Link").length() > 0) {
                    mainEditor.putString("RC_FIRM_LINK", firebaseRemoteConfig.getString("Firm_Download_Link"));
                }
                mainEditor.apply();

                // get app version
                PackageInfo packageInfo = null;
                try {
                    packageInfo = serverContext.getPackageManager().getPackageInfo(serverContext.getPackageName(), 0);
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
                assert packageInfo != null;
                installedAppVersion = packageInfo.versionName;
                if (firebaseRemoteConfig.getString("App_Latest_Version").length() > 0) {
                    remoteConfigVersion = firebaseRemoteConfig.getString("App_Latest_Version");
                }

                if (installedAppVersion.equals(remoteConfigVersion)) {
                    isEqualAppVersion = true;
                } else {
                    String[] installedAppVersionParse = installedAppVersion.split("\\.");
                    String[] remoteConfigVersionParse = remoteConfigVersion.split("\\.");
                    if (Integer.parseInt(installedAppVersionParse[2]) >= Integer.parseInt(remoteConfigVersionParse[2])) {
                        if (Integer.parseInt(installedAppVersionParse[1]) >= Integer.parseInt(remoteConfigVersionParse[1])) {
                            if (Integer.parseInt(installedAppVersionParse[0]) >= Integer.parseInt(remoteConfigVersionParse[0])) {
                                isEqualAppVersion = true;
                            }
                        }
                    } else { // 끝자리가 설치된 값이 더 작을 때
                        if (Integer.parseInt(installedAppVersionParse[1]) > Integer.parseInt(remoteConfigVersionParse[1])) {
                            isEqualAppVersion = true;
                        } else { // 가운데 값도 설치된 값이 더 작거나 같을 때
                            if (Integer.parseInt(installedAppVersionParse[0]) > Integer.parseInt(remoteConfigVersionParse[0])) {
                                isEqualAppVersion = true;
                            }
                        }
                    }
                }
                appVersionCheckIntent.putExtra("REMOTE_CONFIG_RESULT", true);
                appVersionCheckIntent.putExtra("Version_Check", isEqualAppVersion);
            } else {
                Log.d("Remote_Config", String.valueOf(task.getException()));
                appVersionCheckIntent.putExtra("REMOTE_CONFIG_RESULT", false);
            }
            LocalBroadcastManager.getInstance(serverContext).sendBroadcast(appVersionCheckIntent);
        });
    }

    public void getLatestFirmware(Context context, String downloadURL) {
        String fileName = downloadURL.substring(downloadURL.lastIndexOf("/") + 1);
        File downloadDir;
        File savedFile;
        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = Uri.parse(downloadURL);

        if (isExternalStorageWritable()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //29버전 이상
                downloadDir = new File(context.getExternalFilesDir(null), Environment.DIRECTORY_DOWNLOADS + "/RLFirmware/");
                if (!downloadDir.exists()) {
                    downloadDir.mkdir();
                }

                savedFile = new File(downloadDir, fileName);
                if (!savedFile.isFile()) {
                    DownloadManager.Request request = new DownloadManager.Request(uri);
                    request.setTitle("Firmware Download - " + fileName)
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE | DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "/RLFirmware/" + fileName)
                            .setAllowedOverMetered(true);

                    setDownloadQueueId(downloadManager.enqueue(request));
                } else {
                    // 기존에 다운로드한 펌웨어가 있으면 해당 펌웨어를 제외한 나머지는 다 지우기 - 예정
                    alreadyDownloaded();
                }
            } else { // 29버전 미만 - WRITE_EXTERNAL_STORAGE 권한 가능
                downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/RLFirmware/");
                if (!downloadDir.exists()) {
                    downloadDir.mkdir();
                }

                savedFile = new File(downloadDir, fileName);
                if (!savedFile.isFile()) {
                    DownloadManager.Request request = new DownloadManager.Request(uri);
                    request.setTitle("Firmware Download - " + fileName)
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE | DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "/RLFirmware/" + fileName)
                            .setAllowedOverMetered(true);

                    setDownloadQueueId(downloadManager.enqueue(request));
                } else {
                    // 기존에 다운로드한 펌웨어가 있으면 해당 펌웨어를 제외한 나머지는 다 지우기 - 예정
                    alreadyDownloaded();
                }
            }
        } else { // 외부에 할당된 앱 폴더 사용 불가 시 앱 내부에 저장
            downloadDir = new File(context.getFilesDir(), Environment.DIRECTORY_DOWNLOADS + "/RLFirmware/");
            if (!downloadDir.exists()) {
                downloadDir.mkdir();
            }

            savedFile = new File(downloadDir, fileName);
            if (!savedFile.isFile()) {
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setTitle("Firmware Download - " + fileName)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE | DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationUri(uri)
                        .setAllowedOverMetered(true);

                setDownloadQueueId(downloadManager.enqueue(request));
            } else {
                // 기존에 다운로드한 펌웨어가 있으면 해당 펌웨어를 제외한 나머지는 다 지우기
                alreadyDownloaded();
            }
        }
        setFirmwareFile(savedFile);
    }

    private void alreadyDownloaded() {
        Intent alreadyDownloaded = new Intent("ALREADY_DOWNLOADED");
        LocalBroadcastManager.getInstance(serverContext).sendBroadcast(alreadyDownloaded);
    }

    private boolean isExternalStorageWritable() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }

    public int deleteFirmFile() {
        int states = 0;
        try {
            if (getFirmwareFile() == null) {
                states = -1;
            } else { // file is not null
                if (getFirmwareFile().exists()) {
                    if (getFirmwareFile().delete()) {
                        states = 1;
                    } else {
                        states = 2;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return states;
    }

    /*
    private void allocateStorage() {
        StorageManager storageManager = serverContext.getSystemService(StorageManager.class);
        UUID appSpecificInternalDirUuid = storageManager.getUuidForPath(serverContext.getFilesDir());
        long availableBytes = storageManager.getAllocatableBytes(appSpecificInternalDirUuid);

        if (availableBytes >= NUM_BYTES_NEEDED_FOR_FIRMWARE) {
            storageManager.allocateBytes(appSpecificInternalDirUuid, NUM_BYTES_NEEDED_FOR_FIRMWARE);
        } else {
            Intent storageIntent = new Intent();
            storageIntent.setAction(Intent.ACTION_MANAGE_PACKAGE_STORAGE);
        }
    }
    */

    public void initTCP(String tcpAddr, int tcpPort) {
        // TCP 통신을 위한 쓰레드 생성
        Thread tcpConnectionThread = new Thread(() -> {
            try {
                if (tcpSocket == null) {
                    tcpSocket = new Socket(tcpAddr, tcpPort);
                    outputStream = tcpSocket.getOutputStream();
                    if (tcpSocket.isConnected()) {
                        setTcpAvailable(true);
                    }
                }
            } catch (Exception e){
                e.printStackTrace();
            }
        });

        tcpConnectionThread.start();
        try {
            tcpConnectionThread.join();
            setTryTCPConnection(false);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void closeTCP(){
        try{
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (tcpSocket != null) {
                tcpSocket.close();
                if (tcpSocket.isClosed()) {
                    setTcpAvailable(false);
                    tcpSocket = null;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void closeServerFeature(){
        cm.unregisterNetworkCallback(networkCallback);
        closeTCP();
    }

    public long getDownloadQueueId() {
        return mDownloadQueueId;
    }

    private void setDownloadQueueId(long id) {
        mDownloadQueueId = id;
    }

    private void setFirmwareFile(File tempFile) {
        firmware = tempFile;
    }

    public File getFirmwareFile() {
        return firmware;
    }

    public void setNetworkAvailable(boolean _networkAvailable) {
        networkAvailable = _networkAvailable;
    }

    public boolean isNetworkAvailable() {
        return networkAvailable;
    }

    public void setTcpAvailable(boolean _tcpAvailable) {
        tcpAvailable = _tcpAvailable;
    }

    public boolean isTcpAvailable() {
        return tcpAvailable;
    }

    public void setTryTCPConnection(boolean tryToConnection) {
        tryTCPConnection = tryToConnection;
    }

    public boolean isTryTCPConnection() {
        return tryTCPConnection;
    }

    public void writeDB(String sensorAddress, int[] IMU, float[] ATT, float[] GNSS, int[] AC, boolean[] events, String status, int manager_id, int driving_id) {
        // 현재 시간 구함
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.KOREA);

        // Cloud Firestore에 데이터 저장하는 함수
        Map<String, Object> data = new HashMap<>();

        data.put("STATUS", status);
        data.put("MANAGER_ID", manager_id);
        data.put("DRIVING_ID", driving_id);
        data.put("TIME", simpleDateFormat.format(date));
        data.put("ACCEL", Arrays.asList(IMU[0], IMU[1], IMU[2]));
        data.put("ATTITUDE", Arrays.asList(ATT[0], ATT[1], GNSS[2], GNSS[3]));
        data.put("GYRO", Arrays.asList(IMU[3], IMU[4], IMU[5]));
        data.put("AUTO_CORRELATION", Arrays.asList(AC[0], AC[1], AC[2]));
        GeoPoint geoPoint = new GeoPoint(GNSS[0], GNSS[1]);
        String hash = GeoFireUtils.getGeoHashForLocation(new GeoLocation(GNSS[0], GNSS[1]));
        Map<String, Object> geoHashData = new HashMap<>();
        geoHashData.put("geohash", hash);
        geoHashData.put("geopoint", geoPoint);
        data.put("g", geoHashData);
        data.put("EVENTS", Arrays.asList(events[0], events[1], events[2], events[3]));

        // Add a new document with a generated ID
        firebaseFirestore
                .collection("driving")
                .document(sensorAddress)
                .set(data).addOnFailureListener(e -> Log.e("TAG", "Firebase update failed.", e));
    }

    public void writeECall(String username, int managerID, String sensorID, String phone, String message, String detailMSG, boolean checked_manager, boolean checked_admin, LatLng eventLocation) {
        GeoPoint geoPoint = new GeoPoint(eventLocation.latitude, eventLocation.longitude);
        String hash = GeoFireUtils.getGeoHashForLocation(new GeoLocation(eventLocation.latitude, eventLocation.longitude));
        Map<String, Object> geoHashData = new HashMap<>();
        geoHashData.put("geoHash", hash);
        geoHashData.put("geoPoint", geoPoint);

        // Cloud Firestore에 데이터 저장하는 함수
        Map<String, Object> data = new HashMap<>();
        data.put("managerId", managerID);
        data.put("sensorId", sensorID);
        data.put("phoneNum", phone);
        data.put("message", message);
        data.put("detailMessage", detailMSG);
        data.put("status", "requested");
        data.put("errorMessage", "");
        data.put("location", geoHashData);
        data.put("checked", checked_manager);
        data.put("checked2", checked_admin);
        data.put("createdAt", FieldValue.serverTimestamp());
        firebaseFirestore
                .collection("ecalls")
                .document(username)
                .set(data).addOnFailureListener(e -> Log.e("Firebase", "Firebase update failed", e));
    }

    public void writeFile(String sensorAddress, String phoneNum, int[] IMU, int[] AC, float[] ATT,float[] GNSS, boolean[] events) {
        byte[] data = new byte[72]; // 혹시 모르니 몇 byte 추가
        int index = 0;
        data[index++] = (byte) 0xFF;  // SoF
        //data[index++] = (byte) 0x30;  // MSG 길이

        /*
        // 유저 ID 변환
        byte[] usrname = username.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(usrname, 0, data, 2, 8);
        */

        // Sensor Address 변환
        byte[] sensorAddressBytes = sensorAddress.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(sensorAddressBytes, 0, data, index, 12);
        index += 12; // 1부터 12 index까지

        data[index++] = (byte) 0xFE; // EoH - 13index
        data[index++] = (byte) 0x7F; // CRC-16 - 14index
        data[index++] = (byte) 0x7E; // CRC-16 - 15index

        /*
        byte[] phoneNumBytes = phoneNum.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(phoneNumBytes, 0, data, index, phoneNum.length());
        index += phoneNum.length(); // 19부터 11(or10개) -> 29번(or28번) index까지 -> index = 30(or 29);
        */

        // int 형이지만 실제 센서 값의 범위가 -20,000 ~ 20,000 사이이므로 4byte 전부 변환하지 않고 끝 2byte만 변환 (2byte 범위 : -32,000 ~ 32,000)
        // IMU 데이터 배열 변환
        for (int i = 0; i < 6; i++) { // index 10 ~ 21 -> 30 ~ 41
            data[(i * 2) + index] = (byte) ((IMU[i] >> 8) & 0xFF);
            data[(i * 2) + (index + 1)] = (byte) (IMU[i] & 0xFF);
        }
        index += 12; // 30번 index부터 12개 = 41 index까지 IMU 저장 -> 다음 정보는 index 42부터

        // GNSS 데이터 배열 변환
        for(int i = 0; i < 4; i++){ // index 30 ~ 45 -> 56 ~ 71
            byte[] GNSSBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(GNSS[i]).array();
            data[(i * 4) + index] = GNSSBytes[0];
            data[(i * 4) + (index + 1)] = GNSSBytes[1];
            data[(i * 4) + (index + 2)] = GNSSBytes[2];
            data[(i * 4) + (index + 3)] = GNSSBytes[3];
        }
        index += 16; // 56번 index부터 16개 = 71번 index까지 GNSS 저장 -> 다음 정보는 index 72부터

        // ATT 데이터 배열 변환
        for (int i = 0; i < 2; i++) { // index 22 ~ 29 -> 48 ~ 55
            byte[] ATTBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(ATT[i]).array();
            data[(i * 4) + index] = ATTBytes[0];
            data[(i * 4) + (index + 1)] = ATTBytes[1];
            data[(i * 4) + (index + 2)] = ATTBytes[2];
            data[(i * 4) + (index + 3)] = ATTBytes[3];
        }
        index += 8; // 48번 index부터 8개 = 55 index까지 ATT 저장 -> 다음 정보 index 56부터

        data[index++] = (byte) 0x00;
        data[index++] = (byte) 0x00;
        data[index++] = (byte) 0x00;
        data[index++] = (byte) 0x00;

        // AUTO CORRELATION 데이터 배열 변환
        for (int i = 0; i < 3; i++) {
            data[(i * 4) + index] = (byte) ((AC[i] >> 24) & 0xFF);
            data[(i * 4) + (index + 1)] = (byte) ((AC[i] >> 16) & 0xFF);
            data[(i * 4) + (index + 2)] = (byte) ((AC[i] >> 8) & 0xFF);
            data[(i * 4) + (index + 3)] = (byte) (AC[i] & 0xFF);
        }
        index += 12; // 42번 index부터 6개 = 47 index까지 Auto-Correlation 저장 -> 다음 정보는 index 48부터

        data[index++] = (byte)(events[0]?1:0);
        data[index++] = (byte)(events[1]?1:0);
        data[index++] = (byte)(events[2]?1:0);
        data[index] = (byte)(events[3]?1:0); // 72

        if (tcpSocket.isConnected()) {
            try{
                //OutputStream ss = tcpSocket.getOutputStream();
                outputStream.write(data);
                outputStream.flush();

                /*
                InputStream inputStream = tcpSocket.getInputStream();
                if(inputStream.read() == -1) {
                    //close tcp
                    closeTCP();
                }
                */
            } catch (IOException e){
                e.printStackTrace();
                closeTCP();
            }
        }
    }

    public void assignCheck(Context context, String api, String phone) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String requestApi = api + "status";
            StringBuilder sb = new StringBuilder();

            try {
                URL url = new URL(requestApi);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                if (conn != null) {
                    Intent assignCheckIntent = new Intent("ASSIGN_CHECK");

                    conn.setConnectTimeout(1000 * 5);
                    conn.setReadTimeout(1000 * 5);
                    conn.setRequestMethod("POST");

                    conn.setDoOutput(true);
                    conn.setDoInput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");

                    JSONObject requestJsonObject = new JSONObject();
                    requestJsonObject.put("PHONE", phone);

                    OutputStream outputStream = conn.getOutputStream();
                    outputStream.write(requestJsonObject.toString().getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();

                        JSONObject responseJsonObject = new JSONObject(sb.toString());
                        String responseStatus = responseJsonObject.getString("STATUS");
                        assignCheckIntent.putExtra("ASSIGN_CHECK_RESULT", responseStatus);

                        if (responseStatus.equals("SUCCESS")) {
                            assignCheckIntent.putExtra("SENSOR", responseJsonObject.getString("SENSOR"));
                            assignCheckIntent.putExtra("VEHICLE", responseJsonObject.getString("VEHICLE"));
                            assignCheckIntent.putExtra("MANAGER_ID", responseJsonObject.getInt("MANAGER_ID"));
                        } else if (responseStatus.equals("FAILED")) {
                            // 실패 시 CODE 전송
                            // 00 : 라이더 정보 없음, 01 : 연결된 센서 없음, 02 : 연결된 차량 없음
                            assignCheckIntent.putExtra("CODE", responseJsonObject.getString("CODE"));
                        }
                    } else {
                        Log.d("Http Response", "Fail - "+responseCode);
                    }
                    conn.disconnect();

                    assignCheckIntent.putExtra("response_code", responseCode);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(assignCheckIntent);
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        });
    }

    public void riderSensorAssign(Context context, String api, String phone, String sensor) {
        String removeColon = sensor.replace(":", "");
        Executors.newSingleThreadExecutor().execute(() -> {
            String apiAddress = api + "check";
            StringBuilder sb = new StringBuilder();

            try{
                URL url = new URL(apiAddress);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (conn != null){
                    Intent riderSensorAssignIntent = new Intent("ASSIGN_RIDER");

                    // 서버 연결시 time out
                    conn.setConnectTimeout(1000 * 5);
                    // read 시 time out
                    conn.setReadTimeout(1000 * 5);
                    conn.setRequestMethod("POST");

                    // OutputStream으로 Post 데이터를 넘겨주겠다는 옵션
                    conn.setDoOutput(true);
                    // InputStream으로 서버로 부터 응답을 받겠다는 옵션
                    conn.setDoInput(true);

                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");

                    // 서버로 전달할 Json객체 생성
                    JSONObject requestJsonObject = new JSONObject();
                    requestJsonObject.put("PHONE", phone);
                    requestJsonObject.put("SENSOR", removeColon);

                    // Request Body에 데이터를 담기위한 OutputStream 객체 생성
                    OutputStream outputStream;
                    outputStream = conn.getOutputStream();
                    outputStream.write(requestJsonObject.toString().getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        // 결과 값 읽어오는 부분
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        // 버퍼리더 종료
                        br.close();
                        JSONObject responseJsonObject = new JSONObject(sb.toString());

                        String responseStatus = responseJsonObject.getString("STATUS");
                        riderSensorAssignIntent.putExtra("ASSIGN_RESULT", responseStatus);
                        if (responseStatus.equals("SUCCESS")) {
                            riderSensorAssignIntent.putExtra("ASSIGNED_PHONE", phone);
                            riderSensorAssignIntent.putExtra("ASSIGNED_SENSOR", removeColon);
                            riderSensorAssignIntent.putExtra("MANAGER_ID", responseJsonObject.getInt("MANAGER_ID"));
                        } else if (responseStatus.equals("FAILED")) {
                            // 실패 시 CODE 전송
                            // 00 : 센서 정보 없음, 01 : 센서와 차량이 연결되어 있지 않음, 02 : 라이더 정보가 없음
                            riderSensorAssignIntent.putExtra("CODE", responseJsonObject.getString("CODE"));
                        }
                    } else {
                        Log.d("Http Response", "Fail - "+responseCode);
                    }
                    conn.disconnect();

                    riderSensorAssignIntent.putExtra("response_code", responseCode);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(riderSensorAssignIntent);
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        });
    }

    public void releaseAssign(Context context, String api, String phone, String sensor) {
        String removeColon = sensor.replace(":", "");

        Executors.newSingleThreadExecutor().execute(() -> {
            String requestApi = api + "reset";
            StringBuilder sb = new StringBuilder();

            try {
                URL url = new URL(requestApi);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                if (conn != null) {
                    Intent releaseAssignIntent = new Intent("RELEASE_ASSIGN");

                    conn.setConnectTimeout(1000 * 5);
                    conn.setReadTimeout(1000 * 5);
                    conn.setRequestMethod("DELETE");

                    conn.setDoOutput(true);
                    conn.setDoInput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");

                    JSONObject requestJsonObject = new JSONObject();
                    requestJsonObject.put("PHONE", phone);
                    requestJsonObject.put("SENSOR", removeColon);

                    OutputStream outputStream = conn.getOutputStream();
                    outputStream.write(requestJsonObject.toString().getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();

                        JSONObject responseJsonObject = new JSONObject(sb.toString());
                        String responseStatus = responseJsonObject.getString("STATUS");
                        releaseAssignIntent.putExtra("RELEASE_ASSIGN_RESULT", responseStatus);

                        if (responseStatus.equals("FAILED")) {
                            // 실패 시 CODE 전송
                            // 00 : 라이더 정보 없음, 01 : 연결된 센서 없음 or 센서 id 틀림, 02 : 연결된 차량 없음
                            releaseAssignIntent.putExtra("CODE", responseJsonObject.getString("CODE"));
                        }
                    } else {
                        Log.d("Http Response", "Fail - "+responseCode);
                    }
                    conn.disconnect();

                    releaseAssignIntent.putExtra("response_code", responseCode);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(releaseAssignIntent);
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        });
    }

    public void startDriving(Context context, String api, String sensorID) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String apiAddress = api + "start";
            StringBuilder sb = new StringBuilder();

            try{
                URL url = new URL(apiAddress);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                if(conn != null){
                    conn.setConnectTimeout(1000 * 5);
                    conn.setReadTimeout(1000 * 5);
                    conn.setRequestMethod("POST");

                    conn.setDoOutput(true);
                    conn.setDoInput(true);

                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");

                    JSONObject requestJsonObject = new JSONObject();
                    requestJsonObject.put("SENSOR", sensorID);

                    OutputStream outputStream;
                    outputStream = conn.getOutputStream();
                    outputStream.write(requestJsonObject.toString().getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    Intent startDrivingIntent = new Intent("START_DRIVING");

                    int responseCode = conn.getResponseCode();
                    if(responseCode == HttpURLConnection.HTTP_OK){
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while((line = br.readLine()) != null){
                            sb.append(line);
                        }
                        br.close();

                        int drivingID = -1;
                        boolean status = false;
                        JSONObject responseJsonObject = new JSONObject(sb.toString());
                        Iterator<String> keysItr = responseJsonObject.keys();
                        while(keysItr.hasNext()){
                            String key = keysItr.next();
                            String value = responseJsonObject.getString(key);

                            switch (key){
                                case "STATUS":
                                    if(value.equals("SUCCESS")){
                                        status = true;
                                    }
                                    break;
                                case "DRIVING_ID":
                                    drivingID = Integer.parseInt(value);
                                    break;
                            }
                        }
                        if(status){
                            startDrivingIntent.putExtra("DRIVING_STATUS", "driving");
                        }else{
                            startDrivingIntent.putExtra("DRIVING_STATUS", "FAILED");
                        }

                        startDrivingIntent.putExtra("DRIVING_ID", drivingID);
                    }else{
                        Log.d("Http Response", "Fail - "+responseCode);
                    }
                    conn.disconnect();

                    startDrivingIntent.putExtra("RESPONSE_CODE", responseCode);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(startDrivingIntent);
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        });
    }

    public void stopDriving(Context context, String api, String sensorID, int drivingID){
        Executors.newSingleThreadExecutor().execute(() -> {
            String apiAddress = api + "stop";
            StringBuilder sb = new StringBuilder();

            try {
                URL url = new URL(apiAddress);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                if (conn != null) {
                    conn.setConnectTimeout(1000 * 5);
                    conn.setReadTimeout(1000 * 5);
                    conn.setRequestMethod("POST");

                    conn.setDoOutput(true);
                    conn.setDoInput(true);

                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");

                    JSONObject requestJsonObject = new JSONObject();
                    requestJsonObject.put("SENSOR", sensorID);
                    requestJsonObject.put("DRIVING_ID", drivingID);

                    OutputStream outputStream;
                    outputStream = conn.getOutputStream();
                    outputStream.write(requestJsonObject.toString().getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    Intent stopDrivingIntent = new Intent("STOP_DRIVING");

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();

                        JSONObject responseJsonObject = new JSONObject(sb.toString());

                        String value = responseJsonObject.getString("STATUS");
                        if(value.equals("SUCCESS")){
                            stopDrivingIntent.putExtra("DRIVING_STATUS", "stopped");
                        }else{
                            stopDrivingIntent.putExtra("DRIVING_STATUS", "FAILED");
                        }
                    } else {
                        Log.d("Http Response", "Fail - "+responseCode);
                    }
                    conn.disconnect();

                    stopDrivingIntent.putExtra("RESPONSE_CODE", responseCode);
                    LocalBroadcastManager.getInstance(context).sendBroadcast(stopDrivingIntent);
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }

        });
    }

    public void logging(String api, int drivingID, int[] IMU, float[] GNSS, int[] cACC, float[] COLLISION) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String targetAPI = api + "log";
            StringBuilder sb = new StringBuilder();

            try {
                URL url = new URL(targetAPI);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                if (conn != null) {
                    conn.setConnectTimeout(1000 * 5);
                    conn.setReadTimeout(1000 * 5);
                    conn.setRequestMethod("POST");

                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");

                    JSONObject requestJsonObject = new JSONObject();
                    requestJsonObject.put("drivingId", drivingID);
                    requestJsonObject.put("speed", GNSS[2]);
                    requestJsonObject.put("accelX", IMU[0]);
                    requestJsonObject.put("accelY", IMU[1]);
                    requestJsonObject.put("accelZ", IMU[2]);
                    requestJsonObject.put("gyroX", IMU[3]);
                    requestJsonObject.put("gyroY", IMU[4]);
                    requestJsonObject.put("gyroZ", IMU[5]);
                    requestJsonObject.put("latitude", GNSS[0]);
                    requestJsonObject.put("longitude", GNSS[1]);
                    requestJsonObject.put("altitude", GNSS[3]);
                    requestJsonObject.put("cAccelX", cACC[0]);
                    requestJsonObject.put("cAccelY", cACC[1]);
                    requestJsonObject.put("cAccelZ", cACC[2]);
                    requestJsonObject.put("shock", COLLISION[0]);
                    requestJsonObject.put("fall", COLLISION[1]);
                    requestJsonObject.put("direction", COLLISION[2]);
                    requestJsonObject.put("impulse", COLLISION[3]);

                    OutputStream outputStream;
                    outputStream = conn.getOutputStream();
                    outputStream.write(requestJsonObject.toString().getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();
                    } else {
                        Log.d("Http Response", "Fail - "+responseCode);
                    }
                    conn.disconnect();
                }
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        });
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    NETWORK_AVAILABLE_WIFI = true;
                } else if(nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    NETWORK_AVAILABLE_CELLULAR = true;
                }
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);

            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    NETWORK_AVAILABLE_WIFI = false;
                } else if(nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    NETWORK_AVAILABLE_CELLULAR = false;
                }
            }
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);

            Intent networkStatus;
            setNetworkAvailable(false);
            closeTCP();
            if (NETWORK_AVAILABLE_WIFI || NETWORK_AVAILABLE_CELLULAR) {
                networkStatus = new Intent("NETWORK_AVAILABLE");
                Handler networkHandler = new Handler(Looper.myLooper());
                networkHandler.postDelayed(() -> {
                    setNetworkAvailable(true);
                    initTCP(RLGeneralService.destAddr, RLGeneralService.destPort);

                    LocalBroadcastManager.getInstance(serverContext).sendBroadcast(networkStatus);
                },500);
            } else {
                networkStatus = new Intent("NETWORK_UNAVAILABLE");
                LocalBroadcastManager.getInstance(serverContext).sendBroadcast(networkStatus);
            }

        }
    };
}
