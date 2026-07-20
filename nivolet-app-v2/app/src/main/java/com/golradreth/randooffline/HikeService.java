package com.golradreth.randooffline;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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

    static final String SESSION_PREFS = "tracking_session";
    static final String SESSION_TRACKING = "tracking";

    private static final String CHANNEL_ID = "hike_tracking";
    private static final int NOTIFICATION_ID = 73;
    private static final double OFF_ROUTE_WARNING_METERS = 80;

    private LocationManager locationManager;
    private Location previousLocation;
    private Route activeRoute;
    private double walkedMeters;
    private long startedAt;
    private long lastWarningAt;
    private int lastRouteIndex;
    private boolean updatesRequested;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startedAt = System.currentTimeMillis();
        setTrackingState(true);
        startForeground(NOTIFICATION_ID, notification("Initialisation du GPS…"));
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        RouteRepository repository = new RouteRepository(this);
        String routeId = intent == null ? null : intent.getStringExtra(EXTRA_ROUTE_ID);
        if (routeId == null) routeId = repository.getActiveRouteId();
        activeRoute = repository.loadRoute(routeId);
        if (activeRoute == null) activeRoute = repository.loadActiveRoute();

        if (!updatesRequested) requestLocationUpdates();
        return START_STICKY;
    }

    private void requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Autorisation GPS manquante");
            stopSelf();
            return;
        }

        try {
            boolean providerAvailable = false;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        3000L,
                        3f,
                        this
                );
                providerAvailable = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        6000L,
                        8f,
                        this
                );
                providerAvailable = true;
            }

            updatesRequested = providerAvailable;
            if (!providerAvailable) {
                updateNotification("Active la localisation dans les réglages");
            } else {
                updateNotification("Recherche du signal GPS…");
            }
        } catch (SecurityException error) {
            updateNotification("Le suivi GPS a été bloqué");
            stopSelf();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;

        if (previousLocation != null
                && previousLocation.getAccuracy() <= 60
                && location.getAccuracy() <= 60) {
            float step = previousLocation.distanceTo(location);
            long elapsed = Math.max(1L, location.getTime() - previousLocation.getTime());
            float speed = step / (elapsed / 1000f);

            if (step >= 1f && step <= 250f && speed <= 12f) {
                walkedMeters += step;
            }
        }
        previousLocation = location;

        RouteProgress progress = activeRoute == null
                ? new RouteProgress(0, Double.NaN, Double.NaN)
                : RouteMath.progress(
                        location,
                        activeRoute,
                        Math.max(0, lastRouteIndex - 30)
                );
        lastRouteIndex = Math.max(lastRouteIndex, progress.index);

        if (!Double.isNaN(progress.offRouteMeters)
                && progress.offRouteMeters > OFF_ROUTE_WARNING_METERS) {
            long now = System.currentTimeMillis();
            if (now - lastWarningAt > 90_000L) {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(700, 200));
                }
                lastWarningAt = now;
            }
        }

        String message;
        if (Double.isNaN(progress.offRouteMeters)) {
            message = "GPS actif • " + RouteMath.formatDistance(walkedMeters) + " parcourus";
        } else {
            message = "Écart " + RouteMath.formatDistance(progress.offRouteMeters)
                    + " • Reste " + RouteMath.formatDistance(progress.remainingMeters);
        }
        updateNotification(message);

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

    private void updateNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(message));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Suivi de randonnée",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Position GPS et alerte d'écart au tracé");
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void setTrackingState(boolean tracking) {
        SharedPreferences prefs = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(SESSION_TRACKING, tracking).apply();
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (!updatesRequested) requestLocationUpdates();
    }

    @Override
    public void onProviderDisabled(String provider) {
        updateNotification("Signal GPS indisponible");
    }

    @Override
    @Deprecated
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
        setTrackingState(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
