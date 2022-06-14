package com.starpickers.riderloglibrary;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;

public class RLLocationService {
    // Global variables
    private FusedLocationProviderClient fusedLocationProviderClient;
    private SettingsClient settingsClient;
    private Context gContext;
    private static final long UPDATE_INTERVAL_IN_MILLISECONDS = 1000;
    private double lastLocationLat;
    private double lastLocationLon;
    private static double accumulatedDistance;

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            super.onLocationResult(locationResult);
            Intent intent = new Intent("LOCATION_UPDATE");
            float[] data = new float[4];
            for (Location location : locationResult.getLocations()) {
                data[0] = (float) location.getLatitude();
                data[1] = (float) location.getLongitude();
                data[2] = location.getSpeed() * 3.6f;
                data[3] = (float) location.getAltitude();

                if (data[2] >= 4.0 && data[2] < 200.0) {
                    double distance = calculateDistance(lastLocationLat, lastLocationLon, location.getLatitude(), location.getLongitude());
                    if (distance * 1000 < 56.0) {
                        accumulatesDistance(distance, location.getLatitude(), location.getLongitude());
                    }
                }
            }
            intent.putExtra("location_data", data);
            LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
        }
    };

    public RLLocationService() { }

    public void initializeLocation(Context context) {
        if (gContext == null) {
            gContext = context;
        }
        if (fusedLocationProviderClient == null) {
            fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(gContext);
        }
        if (settingsClient == null) {
            settingsClient = LocationServices.getSettingsClient(gContext);
        }

        getLastLocation();
    }

    public void getLastLocation() {
        if (ActivityCompat.checkSelfPermission(gContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(gContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(gContext, gContext.getResources().getString(R.string.not_set_location), Toast.LENGTH_SHORT).show();
        } else {
            if (fusedLocationProviderClient != null) {
                fusedLocationProviderClient.getLastLocation().addOnSuccessListener(location -> {
                    if (location != null) {
                        // 마지막으로 기록된 위치 불러오기
                        Intent intent = new Intent("LOCATION_UPDATE");
                        float[] data = {(float) location.getLatitude(),
                                (float) location.getLongitude(),
                                location.getSpeed() * 3.6f,
                                (float) location.getAltitude()};
                        intent.putExtra("location_data", data);
                        lastLocationLat = location.getLatitude();
                        lastLocationLon = location.getLongitude();
                        LocalBroadcastManager.getInstance(gContext).sendBroadcast(intent);
                    }
                });
            }
        }
    }

    public void requestUpdate(){
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
        locationRequest.setFastestInterval(UPDATE_INTERVAL_IN_MILLISECONDS/2);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
        builder.addLocationRequest(locationRequest);
        LocationSettingsRequest locationSettingsRequest = builder.build();
        /*
        settingsClient.checkLocationSettings(locationSettingsRequest).addOnSuccessListener(new OnSuccessListener<LocationSettingsResponse>() {
            @SuppressLint("MissingPermission")
            @Override
            public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
                fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            }
        });
        */

        settingsClient.checkLocationSettings(locationSettingsRequest).addOnCompleteListener(task -> {
            try {
                task.getResult(ApiException.class);
                if ((ActivityCompat.checkSelfPermission(gContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) &&
                        (ActivityCompat.checkSelfPermission(gContext, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
                    Toast.makeText(gContext, gContext.getResources().getString(R.string.not_set_location), Toast.LENGTH_SHORT).show();
                    return;
                }
                fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            } catch (ApiException exception) {
                switch (exception.getStatusCode()){
                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        Intent resolutionRequired = new Intent("RESOLUTION_REQUIRED");
                        resolutionRequired.putExtra("Resolution_Exception", exception);
                        LocalBroadcastManager.getInstance(gContext).sendBroadcast(resolutionRequired);
                        break;
                    case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                        Log.d("Location Settings", "Settings change to unavailable");
                        break;
                }
            }
        }).addOnFailureListener(e -> {
            Log.e("RIDERLOG", "Location Update Failed Due to :"+ e);
            e.printStackTrace();
        });
    }

    public void stopUpdate(){
        fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        resetAccDistance();
    }

    private void accumulatesDistance(double dist, double lat, double lon) {
        accumulatedDistance += dist;
        lastLocationLat = lat;
        lastLocationLon = lon;
    }

    /*
     * lastLocationLat 지점 1 위도, lastLocationLon 지점 1 경도
     * curLocationLat 지점 2 위도, curLocationLon 지점 2 경도
     */
    private double calculateDistance(double lastLocationLat, double lastLocationLon, double curLocationLat, double curLocationLon) {
        double theta = lastLocationLon - curLocationLon;
        double dist = Math.sin(Math.toRadians(lastLocationLat)) * Math.sin(Math.toRadians(curLocationLat))
                + Math.cos(Math.toRadians(lastLocationLat)) * Math.cos(Math.toRadians(curLocationLat)) * Math.cos(Math.toRadians(theta));
        dist = Math.acos(dist);
        dist = Math.toDegrees(dist);
        dist = dist * 60 * 1.1515;
        dist = dist * 1.609344;

        return dist;
    }

    public void resetAccDistance() {
        accumulatedDistance = 0;
    }

    public double getAccDistance() {
        return accumulatedDistance;
    }
}
