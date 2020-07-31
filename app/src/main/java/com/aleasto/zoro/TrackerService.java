package com.aleasto.zoro;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class TrackerService extends Service {
    private static final String TAG = "ZoroTrackerService";
    private static final String NOTIFICATION_CHANNEL_ID = "ZoroTrackerChannel";
    private static final long UPDATE_FREQUENCY = 30 * 1000; // milliseconds

    private LocationRequest locationRequest;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Notification foregroundNotification;
    private WakeLock wakeLock;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        createNotificationChannel();
        foregroundNotification = makeForegroundNotification();

        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(UPDATE_FREQUENCY);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult != null) {
                    sendLocation(locationResult.getLastLocation());
                    Log.d(TAG, "Got location");
                }
            }
        };

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + "::WakeLock");

        super.onCreate();
    }

    private void sendLocation(Location location) {
        new Thread(() -> {
            try {
                URL url = new URL(BuildConfig.SERVER_ADDRESS + "/locations");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.HTTP_ACCESS_TOKEN);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("time", location.getTime());
                body.put("lat", location.getLatitude());
                body.put("lon", location.getLongitude());
                body.put("alt", location.getAltitude());
                body.put("acc", location.getAccuracy());
                body.put("bat", getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                body.put("net", getNetworkType());
                body.put("sig", getSignalLevel());

                DataOutputStream os = new DataOutputStream(conn.getOutputStream());
                os.writeBytes(body.toString());
                os.flush();
                os.close();

                conn.getResponseCode();
                conn.disconnect();
                Log.d(TAG, "Posted location");
            } catch (IOException | JSONException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private String getNetworkType() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork != null && cm.getNetworkCapabilities(activeNetwork).hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            return "Wi-Fi";

        TelephonyManager tm = getSystemService(TelephonyManager.class);
        switch (tm.getNetworkType()) {
            case TelephonyManager.NETWORK_TYPE_GPRS: return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSDPA: return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA: return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "HSPA";
            case TelephonyManager.NETWORK_TYPE_CDMA: return "CDMA";
            case TelephonyManager.NETWORK_TYPE_EVDO_0: return "CDMA - EvDo rev. 0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A: return "CDMA - EvDo rev. A";
            case TelephonyManager.NETWORK_TYPE_EVDO_B: return "CDMA - EvDo rev. B";
            case TelephonyManager.NETWORK_TYPE_1xRTT: return "CDMA - 1xRTT";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_EHRPD: return "CDMA - eHRPD";
            case TelephonyManager.NETWORK_TYPE_IDEN: return "iDEN";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_GSM: return "GSM";
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA: return "TD_SCDMA";
            case TelephonyManager.NETWORK_TYPE_IWLAN: return "IWLAN";
            default: return "UNKNOWN";
        }
    }

    private int getSignalLevel() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork != null && cm.getNetworkCapabilities(activeNetwork).hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wm = getSystemService(WifiManager.class);
            WifiInfo wi = wm.getConnectionInfo();
            return WifiManager.calculateSignalLevel(wi.getRssi(), 5 /* levels 0~4 */);
        }

        TelephonyManager tm = getSystemService(TelephonyManager.class);
        return tm.getSignalStrength().getLevel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wakeLock.acquire();
        startLocationUpdates();
        startForeground(1, foregroundNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop();
    }

    private void stop() {
        wakeLock.release();
        stopLocationUpdates();
        stopForeground(true);
        stopSelf();
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;

        fusedLocationClient.requestLocationUpdates(locationRequest,
                locationCallback,
                Looper.getMainLooper());
    }

    private void stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    private void createNotificationChannel() {
        CharSequence name = "Tracking service status";
        String description = "";
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification makeForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,0,notificationIntent,0);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Zoro tracking service")
                .setContentText("Service is running")
                .setContentIntent(pendingIntent);
        return builder.build();
    }
}
