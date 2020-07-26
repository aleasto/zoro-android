package com.aleasto.zoro;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStates;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.tasks.Task;
import com.kyleduo.switchbutton.SwitchButton;

import java.io.IOException;
import java.util.Arrays;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CHECK_PERMISSIONS = 1;
    private static final int REQUEST_CHECK_LOCATION_SETTINGS = 2;

    private SwitchButton trackerServiceSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        trackerServiceSwitch = findViewById(R.id.tracker_service_switch);
        trackerServiceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            //if (!buttonView.isPressed()) return;

            if (isChecked) {
                // Ask for permission and start tracker service
                ActivityCompat.requestPermissions(
                        this, new String[] {
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                                Manifest.permission.READ_PHONE_STATE,
                        }, REQUEST_CHECK_PERMISSIONS);
            } else {
                stopTrackerService();
            }
        });

        updateTrackerSwitch();
    }

    private void requestLocationAccess() {
        LocationSettingsRequest.Builder locationSettingsBuilder = new LocationSettingsRequest.Builder()
                .addLocationRequest(LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY));
        Task<LocationSettingsResponse> result =
                LocationServices.getSettingsClient(this).checkLocationSettings(locationSettingsBuilder.build());
        result.addOnCompleteListener(task -> {
            try {
                LocationSettingsResponse response = task.getResult(ApiException.class);
                // All location settings are satisfied. The client can initialize location
                // requests here.
                startTrackerService();
            } catch (ApiException exception) {
                switch (exception.getStatusCode()) {
                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        try {
                            ResolvableApiException resolvable = (ResolvableApiException) exception;
                            resolvable.startResolutionForResult(
                                    MainActivity.this,
                                    REQUEST_CHECK_LOCATION_SETTINGS);
                        } catch (IntentSender.SendIntentException | ClassCastException ignored) {}
                        break;
                    case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                        stopTrackerService();
                        break;
                }
            }
        });
    }

    private void requestDisableDoze() {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "su", "-c", "dumpsys", "deviceidle", "disable" });
            p.waitFor();
            if (p.exitValue() != 0) throw new IOException();
            requestLocationAccess();
        } catch (IOException | InterruptedException e) {
            Toast.makeText(this, "Please grant root access to continue.", Toast.LENGTH_LONG).show();
            e.printStackTrace();
            stopTrackerService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        switch (requestCode) {
            case REQUEST_CHECK_LOCATION_SETTINGS:
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        startTrackerService();
                        break;
                    case Activity.RESULT_CANCELED:
                        stopTrackerService();
                        break;
                    default:
                        break;
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (Arrays.stream(grantResults).allMatch(i -> i == PERMISSION_GRANTED))
            requestDisableDoze(); // and request location access, then start service
    }

    private synchronized void startTrackerService() {
        Intent intent = new Intent(this, TrackerService.class);
        startForegroundService(intent);
        updateTrackerSwitch();
    }

    private synchronized void stopTrackerService() {
        // attempt to restore doze state
        try {
            Runtime.getRuntime().exec(new String[] { "su", "-c", "dumpsys", "deviceidle", "enable" });
        } catch (IOException ignored) { }
        Intent intent = new Intent(this, TrackerService.class);
        stopService(intent);
        updateTrackerSwitch();
    }

    private synchronized void updateTrackerSwitch() {
        trackerServiceSwitch.setCheckedNoEvent(isTrackerServiceRunning());
    }

    @SuppressWarnings("deprecation")
    private synchronized boolean isTrackerServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (TrackerService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}