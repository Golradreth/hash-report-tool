package com.golradreth.randooffline;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class HikeService extends Service implements LocationListener {
    static final String ACTION_LOCATION = "com.golradreth.randooffline.LOCATION";
    static final String EXTRA_LOCATION = "location";
    static final String EXTRA_WALKED = "walked";
    static final String EXTRA_ELAPSED = "elapsed";
    static final String EXTRA_ROUTE_ID = "route_id";

    private static final String CHANNEL_ID = "hike_tracking";
    private static final int NOTIFICATION_ID = 73;
    private static final double OFF_ROUTE_WARNING_METERS = 80;

    private LocationManager locationManager;
    private Location previousLocation;
    private Route activeRoute;
    private double walkedMeters;
    private long startedAt;
    private long lastWarningAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startedAt = System.currentTimeMillis();
        startForeground(NOTIFICATION_ID, notification("Recherche du signal GPS…"));
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        requestLocationUpdates();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        activeRoute = new RouteRepository(this).loadActiveRoute();
        return START_STICKY;
    }

    private void requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 3f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 6000L, 8f, this);
            }
        } catch (SecurityException ignored) {
            stopSelf();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (previousLocation != null
                && previousLocation.getAccuracy() <= 50
                && location.getAccuracy() <= 50) {
            float step = previousLocation.distanceTo(location);
            if (step >= 1f && step <= 250f) walkedMeters += step;
        }
        previousLocation = location;

        double offRoute = activeRoute == null ? Double.NaN : RouteMath.nearestDistance(location, activeRoute);
        if (!Double.isNaN(offRoute) && offRoute > OFF_ROUTE_WARNING_METERS) {
            long now = System.currentTimeMillis();
            if (now - lastWarningAt > 90_000L) {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(700, 200));
                }
                lastWarningAt = now;
            }
        }

        String notificationText = Double.isNaN(offRoute)
                ? "GPS actif • " + RouteMath.formatDistance(walkedMeters) + " parcourus"
                : "Écart trace : " + RouteMath.formatDistance(offRoute)
                    + " • " + RouteMath.formatDistance(walkedMeters) + " parcourus";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(notificationText));

        Intent broadcast = new Intent(ACTION_LOCATION);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_LOCATION, location);
        broadcast.putExtra(EXTRA_WALKED, walkedMeters);
        broadcast.putExtra(EXTRA_ELAPSED, System.currentTimeMillis() - startedAt);
        sendBroadcast(broadcast);
    }

    private Notification notification(String message) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Suivi randonnée actif")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Suivi de randonnée",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Position GPS et alerte d'écart au tracé");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    @Deprecated
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
