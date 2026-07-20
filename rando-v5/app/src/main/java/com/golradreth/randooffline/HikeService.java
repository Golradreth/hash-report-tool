package com.golradreth.randooffline;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class HikeService extends Service implements LocationListener {
    static final String ACTION_LOCATION = "com.golradreth.randooffline.LOCATION";
    static final String ACTION_NEW = "com.golradreth.randooffline.NEW";
    static final String ACTION_RESUME = "com.golradreth.randooffline.RESUME";
    static final String ACTION_PAUSE = "com.golradreth.randooffline.PAUSE";
    static final String ACTION_FINISH = "com.golradreth.randooffline.FINISH";

    static final String EXTRA_LOCATION = "location";
    static final String EXTRA_WALKED = "walked";
    static final String EXTRA_ELAPSED = "elapsed";
    static final String EXTRA_ROUTE_ID = "route_id";
    static final String EXTRA_ROUTE_INDEX = "route_index";

    static final String SESSION_PREFS = "tracking_session_v5";
    static final String SESSION_EXISTS = "exists";
    static final String SESSION_TRACKING = "tracking";
    static final String SESSION_ROUTE_ID = "route_id";
    static final String SESSION_WALKED = "walked";
    static final String SESSION_ELAPSED = "elapsed";
    static final String SESSION_ROUTE_INDEX = "route_index";
    static final String SESSION_HAS_LOCATION = "has_location";
    static final String SESSION_LAT = "lat";
    static final String SESSION_LON = "lon";
    static final String SESSION_ELE = "ele";
    static final String SESSION_HAS_ELE = "has_ele";
    static final String SESSION_ACCURACY = "accuracy";
    static final String SESSION_BEARING = "bearing";
    static final String SESSION_HAS_BEARING = "has_bearing";
    static final String SESSION_SPEED = "speed";
    static final String SESSION_LOCATION_TIME = "location_time";

    private static final String PREVIOUS_HAS = "previous_has";
    private static final String PREVIOUS_LAT = "previous_lat";
    private static final String PREVIOUS_LON = "previous_lon";
    private static final String PREVIOUS_TIME = "previous_time";
    private static final String PREVIOUS_ACCURACY = "previous_accuracy";
    private static final String PREVIOUS_ELE = "previous_ele";
    private static final String PREVIOUS_HAS_ELE = "previous_has_ele";
    private static final String TRACK_LAST_HAS = "track_last_has";
    private static final String TRACK_LAST_LAT = "track_last_lat";
    private static final String TRACK_LAST_LON = "track_last_lon";
    private static final String TRACK_LAST_TIME = "track_last_time";

    private static final String CHANNEL_ID = "hike_tracking_v5";
    private static final int NOTIFICATION_ID = 73;
    private static final double OFF_ROUTE_WARNING_METERS = 80;
    private static final String TRACK_FILE = "session_track_v5.csv";

    private LocationManager locationManager;
    private Location previousLocation;
    private Location lastTrackLocation;
    private Route activeRoute;
    private SharedPreferences prefs;
    private double walkedMeters;
    private long elapsedMs;
    private long clockStartedAt;
    private long lastWarningAt;
    private int lastRouteIndex;
    private boolean updatesRequested;
    private boolean explicitPauseOrFinish;

    @Override public void onCreate() {
        super.onCreate();
        prefs = sessionPrefs(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("Préparation du suivi GPS…"));
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_FINISH.equals(action)) {
            explicitPauseOrFinish = true;
            clearSession(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            explicitPauseOrFinish = true;
            persistClock();
            prefs.edit().putBoolean(SESSION_TRACKING, false).commit();
            stopSelf();
            return START_NOT_STICKY;
        }

        String requestedRoute = intent == null ? null : intent.getStringExtra(EXTRA_ROUTE_ID);
        if (ACTION_NEW.equals(action)) {
            if (requestedRoute == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            initialiseNewSession(requestedRoute);
        } else if (!prefs.getBoolean(SESSION_EXISTS, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        restoreSession();
        if (requestedRoute != null && !requestedRoute.equals(prefs.getString(SESSION_ROUTE_ID, null))) {
            initialiseNewSession(requestedRoute);
            restoreSession();
        }

        RouteRepository repository = new RouteRepository(this);
        activeRoute = repository.loadRoute(prefs.getString(SESSION_ROUTE_ID, null));
        if (activeRoute == null) activeRoute = repository.loadActiveRoute();
        if (activeRoute == null) {
            updateNotification("Randonnée introuvable");
            explicitPauseOrFinish = true;
            prefs.edit().putBoolean(SESSION_TRACKING, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        prefs.edit().putBoolean(SESSION_EXISTS, true).putBoolean(SESSION_TRACKING, true)
                .putString(SESSION_ROUTE_ID, activeRoute.id).commit();
        clockStartedAt = System.currentTimeMillis();
        updateNotification("Recherche du signal GPS…");
        if (!updatesRequested) requestLocationUpdates();
        return START_STICKY;
    }

    private void initialiseNewSession(String routeId) {
        explicitPauseOrFinish = false;
        walkedMeters = 0;
        elapsedMs = 0;
        lastRouteIndex = 0;
        previousLocation = null;
        lastTrackLocation = null;
        trackFile(this).delete();
        prefs.edit().clear()
                .putBoolean(SESSION_EXISTS, true)
                .putBoolean(SESSION_TRACKING, true)
                .putString(SESSION_ROUTE_ID, routeId)
                .putLong(SESSION_WALKED, Double.doubleToRawLongBits(0d))
                .putLong(SESSION_ELAPSED, 0L)
                .putInt(SESSION_ROUTE_INDEX, 0)
                .commit();
    }

    private void restoreSession() {
        walkedMeters = Double.longBitsToDouble(prefs.getLong(SESSION_WALKED,
                Double.doubleToRawLongBits(0d)));
        elapsedMs = prefs.getLong(SESSION_ELAPSED, 0L);
        lastRouteIndex = prefs.getInt(SESSION_ROUTE_INDEX, 0);
        previousLocation = loadLocation(PREVIOUS_HAS, PREVIOUS_LAT, PREVIOUS_LON,
                PREVIOUS_ELE, PREVIOUS_HAS_ELE, PREVIOUS_ACCURACY, null, null, PREVIOUS_TIME);
        lastTrackLocation = loadLocation(TRACK_LAST_HAS, TRACK_LAST_LAT, TRACK_LAST_LON,
                PREVIOUS_ELE, PREVIOUS_HAS_ELE, PREVIOUS_ACCURACY, null, null, TRACK_LAST_TIME);
    }

    private Location loadLocation(String hasKey, String latKey, String lonKey, String eleKey,
                                  String hasEleKey, String accuracyKey, String bearingKey,
                                  String speedKey, String timeKey) {
        if (!prefs.getBoolean(hasKey, false)) return null;
        Location location = new Location("saved");
        location.setLatitude(Double.longBitsToDouble(prefs.getLong(latKey, 0L)));
        location.setLongitude(Double.longBitsToDouble(prefs.getLong(lonKey, 0L)));
        if (prefs.getBoolean(hasEleKey, false)) {
            location.setAltitude(Double.longBitsToDouble(prefs.getLong(eleKey, 0L)));
        }
        location.setAccuracy(prefs.getFloat(accuracyKey, 30f));
        if (bearingKey != null && prefs.contains(bearingKey)) location.setBearing(prefs.getFloat(bearingKey, 0f));
        if (speedKey != null && prefs.contains(speedKey)) location.setSpeed(prefs.getFloat(speedKey, 0f));
        location.setTime(prefs.getLong(timeKey, System.currentTimeMillis()));
        return location;
    }

    private void requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Autorisation GPS manquante");
            explicitPauseOrFinish = true;
            prefs.edit().putBoolean(SESSION_TRACKING, false).apply();
            stopSelf();
            return;
        }
        try {
            boolean providerAvailable = false;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        3000L, 3f, this);
                providerAvailable = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        6000L, 8f, this);
                providerAvailable = true;
            }
            updatesRequested = providerAvailable;
            updateNotification(providerAvailable ? "Recherche du signal GPS…"
                    : "Active la localisation dans les réglages");
        } catch (SecurityException error) {
            updateNotification("Le suivi GPS a été bloqué");
            explicitPauseOrFinish = true;
            prefs.edit().putBoolean(SESSION_TRACKING, false).apply();
            stopSelf();
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (location == null) return;
        updateElapsedClock();

        if (previousLocation != null && previousLocation.getAccuracy() <= 60
                && location.getAccuracy() <= 60) {
            float step = previousLocation.distanceTo(location);
            long delta = Math.max(1L, location.getTime() - previousLocation.getTime());
            float speed = step / (delta / 1000f);
            if (step >= 1f && step <= 250f && speed <= 12f) walkedMeters += step;
        }
        previousLocation = new Location(location);

        RouteProgress progress = activeRoute == null
                ? new RouteProgress(0, Double.NaN, Double.NaN)
                : RouteMath.progress(location, activeRoute, Math.max(0, lastRouteIndex - 30));
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

        appendTrackIfNeeded(location);
        persistSession(location);

        String message = Double.isNaN(progress.offRouteMeters)
                ? "GPS actif • " + RouteMath.formatDistance(walkedMeters) + " parcourus"
                : "Écart " + RouteMath.formatDistance(progress.offRouteMeters)
                + " • Reste " + RouteMath.formatDistance(progress.remainingMeters);
        updateNotification(message);

        Intent broadcast = new Intent(ACTION_LOCATION);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_LOCATION, location);
        broadcast.putExtra(EXTRA_WALKED, walkedMeters);
        broadcast.putExtra(EXTRA_ELAPSED, elapsedMs);
        broadcast.putExtra(EXTRA_ROUTE_INDEX, lastRouteIndex);
        sendBroadcast(broadcast);
    }

    private void updateElapsedClock() {
        long now = System.currentTimeMillis();
        if (clockStartedAt > 0) elapsedMs += Math.max(0, now - clockStartedAt);
        clockStartedAt = now;
    }

    private void persistClock() {
        updateElapsedClock();
        prefs.edit().putLong(SESSION_ELAPSED, elapsedMs).commit();
    }

    private void persistSession(Location location) {
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(SESSION_EXISTS, true)
                .putBoolean(SESSION_TRACKING, true)
                .putLong(SESSION_WALKED, Double.doubleToRawLongBits(walkedMeters))
                .putLong(SESSION_ELAPSED, elapsedMs)
                .putInt(SESSION_ROUTE_INDEX, lastRouteIndex)
                .putBoolean(SESSION_HAS_LOCATION, true)
                .putLong(SESSION_LAT, Double.doubleToRawLongBits(location.getLatitude()))
                .putLong(SESSION_LON, Double.doubleToRawLongBits(location.getLongitude()))
                .putBoolean(SESSION_HAS_ELE, location.hasAltitude())
                .putFloat(SESSION_ACCURACY, location.getAccuracy())
                .putBoolean(SESSION_HAS_BEARING, location.hasBearing())
                .putFloat(SESSION_SPEED, location.hasSpeed() ? location.getSpeed() : 0f)
                .putLong(SESSION_LOCATION_TIME, location.getTime())
                .putBoolean(PREVIOUS_HAS, true)
                .putLong(PREVIOUS_LAT, Double.doubleToRawLongBits(location.getLatitude()))
                .putLong(PREVIOUS_LON, Double.doubleToRawLongBits(location.getLongitude()))
                .putBoolean(PREVIOUS_HAS_ELE, location.hasAltitude())
                .putFloat(PREVIOUS_ACCURACY, location.getAccuracy())
                .putLong(PREVIOUS_TIME, location.getTime());
        if (location.hasAltitude()) {
            editor.putLong(SESSION_ELE, Double.doubleToRawLongBits(location.getAltitude()));
            editor.putLong(PREVIOUS_ELE, Double.doubleToRawLongBits(location.getAltitude()));
        }
        if (location.hasBearing()) editor.putFloat(SESSION_BEARING, location.getBearing());
        editor.commit();
    }

    private void appendTrackIfNeeded(Location location) {
        boolean shouldAppend = lastTrackLocation == null
                || lastTrackLocation.distanceTo(location) >= 2f
                || location.getTime() - lastTrackLocation.getTime() >= 10_000L;
        if (!shouldAppend) return;
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(trackFile(this), true), StandardCharsets.UTF_8))) {
            writer.println(location.getLatitude() + "," + location.getLongitude() + ","
                    + (location.hasAltitude() ? location.getAltitude() : Double.NaN) + ","
                    + location.getTime());
            lastTrackLocation = new Location(location);
            prefs.edit().putBoolean(TRACK_LAST_HAS, true)
                    .putLong(TRACK_LAST_LAT, Double.doubleToRawLongBits(location.getLatitude()))
                    .putLong(TRACK_LAST_LON, Double.doubleToRawLongBits(location.getLongitude()))
                    .putLong(TRACK_LAST_TIME, location.getTime()).apply();
        } catch (Exception ignored) {}
    }

    private Notification notification(String message) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Suivi de randonnée", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Position GPS, progression et alerte hors tracé");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public void onProviderEnabled(String provider) {
        if (!updatesRequested) requestLocationUpdates();
    }

    @Override public void onProviderDisabled(String provider) {
        updateNotification("Signal GPS indisponible");
    }

    @Override @Deprecated public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
        if (!explicitPauseOrFinish && prefs != null && prefs.getBoolean(SESSION_EXISTS, false)) {
            persistClock();
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static SharedPreferences sessionPrefs(Context context) {
        return context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasSession(Context context) {
        return sessionPrefs(context).getBoolean(SESSION_EXISTS, false);
    }

    static void markPausedAfterReboot(Context context) {
        SharedPreferences prefs = sessionPrefs(context);
        if (prefs.getBoolean(SESSION_EXISTS, false)) {
            prefs.edit().putBoolean(SESSION_TRACKING, false).apply();
        }
    }

    static void clearSession(Context context) {
        sessionPrefs(context).edit().clear().commit();
        trackFile(context).delete();
    }

    static ArrayList<GeoPoint> loadTrack(Context context) {
        ArrayList<GeoPoint> points = new ArrayList<>();
        File file = trackFile(context);
        if (!file.exists()) return points;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                points.add(new GeoPoint(Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
            }
        } catch (Exception ignored) {}
        return points;
    }

    private static File trackFile(Context context) {
        return new File(context.getFilesDir(), TRACK_FILE);
    }
}
