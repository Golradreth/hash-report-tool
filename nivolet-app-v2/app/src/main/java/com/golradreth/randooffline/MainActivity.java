package com.golradreth.randooffline;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_GPX = 40;
    private static final int REQUEST_PERMISSIONS = 41;
    private static final int OFF_ROUTE_WARNING_METERS = 80;

    private final List<Route> routes = new ArrayList<>();
    private RouteRepository repository;
    private Route activeRoute;
    private Spinner routeSpinner;
    private TextView routeInfo;
    private TextView liveInfo;
    private TextView status;
    private RouteView routeView;
    private Button trackingButton;

    private Location lastLocation;
    private GeoPoint parkingPoint;
    private boolean tracking;
    private boolean receiverRegistered;
    private double walkedMeters;
    private long elapsedMillis;
    private int lastRouteIndex;
    private long lastUiWarningAt;

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Location location;
            if (Build.VERSION.SDK_INT >= 33) {
                location = intent.getParcelableExtra(HikeService.EXTRA_LOCATION, Location.class);
            } else {
                location = intent.getParcelableExtra(HikeService.EXTRA_LOCATION);
            }
            if (location == null) return;

            lastLocation = location;
            walkedMeters = intent.getDoubleExtra(HikeService.EXTRA_WALKED, walkedMeters);
            elapsedMillis = intent.getLongExtra(HikeService.EXTRA_ELAPSED, elapsedMillis);
            routeView.setCurrent(location);
            refreshLiveData();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        repository = new RouteRepository(this);
        try {
            repository.installBundledNivolet();
        } catch (Exception error) {
            Toast.makeText(this, "Le GPX Nivolet n'a pas pu être préchargé", Toast.LENGTH_LONG).show();
        }

        parkingPoint = loadParking();
        buildInterface();
        reloadRoutes(repository.getActiveRouteId());
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 244, 235));

        TextView title = text("Rando Savoie Offline", 23, true);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.rgb(24, 66, 45));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        routeSpinner = new Spinner(this);
        routeSpinner.setPadding(dp(12), dp(5), dp(12), dp(5));
        root.addView(routeSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        routeInfo = text("", 16, false);
        routeInfo.setPadding(dp(14), dp(4), dp(14), dp(4));
        root.addView(routeInfo);

        status = text("Prêt", 15, true);
        status.setTextColor(Color.rgb(24, 66, 45));
        status.setPadding(dp(14), dp(5), dp(14), dp(5));
        root.addView(status);

        routeView = new RouteView(this);
        routeView.setParking(parkingPoint);
        root.addView(routeView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        liveInfo = text("GPS non démarré", 15, false);
        liveInfo.setPadding(dp(14), dp(7), dp(14), dp(7));
        root.addView(liveInfo);

        LinearLayout firstRow = horizontalRow();
        Button importButton = button("Ajouter un GPX");
        Button deleteButton = button("Supprimer");
        firstRow.addView(importButton, weighted());
        firstRow.addView(deleteButton, weighted());
        root.addView(firstRow);

        LinearLayout secondRow = horizontalRow();
        trackingButton = button("Démarrer le suivi");
        Button parkingButton = button("Marquer parking");
        secondRow.addView(trackingButton, weighted());
        secondRow.addView(parkingButton, weighted());
        root.addView(secondRow);

        LinearLayout thirdRow = horizontalRow();
        Button shareButton = button("Partager position");
        Button safetyButton = button("Sécurité");
        Button sosButton = button("SOS 112");
        sosButton.setTextColor(Color.rgb(160, 25, 20));
        thirdRow.addView(shareButton, weighted());
        thirdRow.addView(safetyButton, weighted());
        thirdRow.addView(sosButton, weighted());
        root.addView(thirdRow);

        setContentView(root);

        routeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < routes.size()) selectRoute(routes.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        importButton.setOnClickListener(view -> openGpxPicker());
        deleteButton.setOnClickListener(view -> deleteActiveRoute());
        trackingButton.setOnClickListener(view -> toggleTracking());
        parkingButton.setOnClickListener(view -> markParking());
        shareButton.setOnClickListener(view -> sharePosition());
        safetyButton.setOnClickListener(view -> showSafetyDialog());
        sosButton.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))));
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(38, 45, 40));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(dp(48));
        return button;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), dp(3));
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openGpxPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_GPX);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GPX || resultCode != RESULT_OK || data == null || data.getData() == null) return;

        try {
            Route imported = repository.importGpx(data.getData());
            reloadRoutes(imported.id);
            status("Randonnée ajoutée : " + imported.name, false);
        } catch (Exception error) {
            Toast.makeText(this, "GPX invalide : " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void reloadRoutes(String selectedId) {
        routes.clear();
        routes.addAll(repository.listRoutes());

        List<String> labels = new ArrayList<>();
        for (Route route : routes) labels.add(route.name);
        if (labels.isEmpty()) labels.add("Aucune randonnée");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        routeSpinner.setAdapter(adapter);

        if (routes.isEmpty()) {
            activeRoute = null;
            routeView.setRoute(null);
            routeInfo.setText("Ajoute un fichier GPX pour commencer.");
            refreshLiveData();
            return;
        }

        int index = 0;
        if (selectedId != null) {
            for (int i = 0; i < routes.size(); i++) {
                if (selectedId.equals(routes.get(i).id)) {
                    index = i;
                    break;
                }
            }
        }
        routeSpinner.setSelection(index);
        selectRoute(routes.get(index));
    }

    private void selectRoute(Route route) {
        activeRoute = route;
        lastRouteIndex = 0;
        repository.setActiveRouteId(route.id);
        routeView.setRoute(route);
        routeView.setParking(parkingPoint);
        routeInfo.setText(RouteMath.formatDistance(route.lengthMeters)
                + "  •  D+ " + Math.round(route.ascentMeters) + " m"
                + "  •  " + route.points.size() + " points");
        if (!tracking) status("Randonnée sélectionnée", false);
        refreshLiveData();
    }

    private void deleteActiveRoute() {
        if (activeRoute == null) return;
        String routeName = activeRoute.name;
        new AlertDialog.Builder(this)
                .setTitle("Supprimer la randonnée ?")
                .setMessage(routeName + " sera retirée de l'application. Tu pourras toujours la réimporter.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (dialog, which) -> {
                    repository.delete(activeRoute.id);
                    reloadRoutes(null);
                    status("Randonnée supprimée", false);
                })
                .show();
    }

    private void toggleTracking() {
        if (tracking) {
            stopTracking();
            return;
        }
        if (activeRoute == null) {
            Toast.makeText(this, "Sélectionne d'abord une randonnée", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startTracking();
    }

    private void startTracking() {
        tracking = true;
        walkedMeters = 0;
        elapsedMillis = 0;
        lastRouteIndex = 0;
        lastUiWarningAt = 0;
        trackingButton.setText("Arrêter le suivi");
        status("Suivi GPS actif — garde le téléphone chargé", false);

        Intent service = new Intent(this, HikeService.class);
        service.putExtra(HikeService.EXTRA_ROUTE_ID, activeRoute.id);
        startForegroundService(service);
    }

    private void stopTracking() {
        tracking = false;
        stopService(new Intent(this, HikeService.class));
        trackingButton.setText("Démarrer le suivi");
        status("Suivi arrêté", false);
        refreshLiveData();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        } else {
            status("Autorisation GPS refusée", true);
        }
    }

    private void refreshLiveData() {
        if (activeRoute == null) {
            liveInfo.setText("Aucune randonnée sélectionnée");
            return;
        }

        String altitude = "—";
        String accuracy = "—";
        String remaining = "—";
        String offRoute = "—";
        String parkingDistance = parkingPoint == null || lastLocation == null
                ? "—" : RouteMath.formatDistance(RouteMath.distance(lastLocation, parkingPoint));

        if (lastLocation != null) {
            altitude = lastLocation.hasAltitude() ? Math.round(lastLocation.getAltitude()) + " m" : "—";
            accuracy = Math.round(lastLocation.getAccuracy()) + " m";

            RouteProgress progress = RouteMath.progress(lastLocation, activeRoute, tracking ? Math.max(0, lastRouteIndex - 25) : 0);
            if (tracking) lastRouteIndex = Math.max(lastRouteIndex, progress.index);
            remaining = RouteMath.formatDistance(progress.remainingMeters);
            offRoute = RouteMath.formatDistance(progress.offRouteMeters);

            if (progress.offRouteMeters > OFF_ROUTE_WARNING_METERS) {
                long now = System.currentTimeMillis();
                status("⚠ Écart d'environ " + Math.round(progress.offRouteMeters) + " m par rapport au tracé", true);
                if (tracking && now - lastUiWarningAt > 60_000L) {
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500, 180));
                    }
                    lastUiWarningAt = now;
                }
            } else if (tracking) {
                status("Sur le tracé — suivi GPS actif", false);
            }
        }

        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = batteryManager == null ? -1
                : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        String batteryText = battery < 0 ? "—" : battery + " %";

        liveInfo.setText(
                "Marché : " + RouteMath.formatDistance(walkedMeters)
                        + "   •   Reste : " + remaining
                        + "\nÉcart trace : " + offRoute
                        + "   •   Parking : " + parkingDistance
                        + "\nAltitude : " + altitude
                        + "   •   Précision : " + accuracy
                        + "\nTemps : " + formatElapsed(elapsedMillis)
                        + "   •   Batterie : " + batteryText
        );
    }

    private String formatElapsed(long millis) {
        long totalMinutes = millis / 60_000L;
        return String.format(Locale.FRANCE, "%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    private void markParking() {
        if (lastLocation != null) {
            parkingPoint = new GeoPoint(lastLocation.getLatitude(), lastLocation.getLongitude(), lastLocation.getAltitude());
        } else if (activeRoute != null && !activeRoute.points.isEmpty()) {
            parkingPoint = activeRoute.points.get(0);
        } else {
            Toast.makeText(this, "Position indisponible", Toast.LENGTH_SHORT).show();
            return;
        }

        saveParking(parkingPoint);
        routeView.setParking(parkingPoint);
        status("Parking mémorisé hors ligne", false);
        refreshLiveData();
    }

    private void sharePosition() {
        GeoPoint point = null;
        if (lastLocation != null) {
            point = new GeoPoint(lastLocation.getLatitude(), lastLocation.getLongitude(), lastLocation.getAltitude());
        } else if (parkingPoint != null) {
            point = parkingPoint;
        }
        if (point == null) {
            Toast.makeText(this, "Aucune position disponible", Toast.LENGTH_SHORT).show();
            return;
        }

        String text = "Ma position : https://www.openstreetmap.org/?mlat="
                + point.lat + "&mlon=" + point.lon + "#map=16/" + point.lat + "/" + point.lon;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, "Partager ma position"));
    }

    private void showSafetyDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Avant de partir")
                .setMessage("• Téléphone chargé et mode économie désactivé pour le suivi\n"
                        + "• Eau, vêtements adaptés et météo vérifiée\n"
                        + "• Prévenir quelqu'un de l'itinéraire et de l'heure de retour\n"
                        + "• Le tracé est disponible hors ligne, mais cette application n'affiche pas de fond de carte IGN\n"
                        + "• Suivre le balisage sur place et faire demi-tour si les conditions deviennent mauvaises\n\n"
                        + "En cas d'urgence : 112")
                .setPositiveButton("Compris", null)
                .show();
    }

    private void status(String message, boolean warning) {
        status.setText(message);
        status.setTextColor(warning ? Color.rgb(174, 35, 29) : Color.rgb(24, 66, 45));
    }

    private void saveParking(GeoPoint point) {
        getSharedPreferences("parking", MODE_PRIVATE).edit()
                .putLong("lat", Double.doubleToRawLongBits(point.lat))
                .putLong("lon", Double.doubleToRawLongBits(point.lon))
                .putBoolean("saved", true)
                .apply();
    }

    private GeoPoint loadParking() {
        SharedPreferences prefs = getSharedPreferences("parking", MODE_PRIVATE);
        if (!prefs.getBoolean("saved", false)) return null;
        return new GeoPoint(
                Double.longBitsToDouble(prefs.getLong("lat", 0L)),
                Double.longBitsToDouble(prefs.getLong("lon", 0L)),
                Double.NaN
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(HikeService.ACTION_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(locationReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            unregisterReceiver(locationReceiver);
            receiverRegistered = false;
        }
    }
}

final class GeoPoint {
    final double lat;
    final double lon;
    final double ele;

    GeoPoint(double lat, double lon, double ele) {
        this.lat = lat;
        this.lon = lon;
        this.ele = ele;
    }
}

final class Route {
    final String id;
    final String name;
    final ArrayList<GeoPoint> points;
    final double lengthMeters;
    final double ascentMeters;

    Route(String id, String name, List<GeoPoint> points) {
        this.id = id;
        this.name = name;
        this.points = new ArrayList<>(points);
        this.lengthMeters = RouteMath.pathLength(this.points);
        this.ascentMeters = RouteMath.ascent(this.points);
    }
}

final class RouteProgress {
    final int index;
    final double offRouteMeters;
    final double remainingMeters;

    RouteProgress(int index, double offRouteMeters, double remainingMeters) {
        this.index = index;
        this.offRouteMeters = offRouteMeters;
        this.remainingMeters = remainingMeters;
    }
}

final class RouteMath {
    private RouteMath() {
    }

    static RouteProgress progress(Location location, Route route, int startIndex) {
        if (route == null || route.points.isEmpty()) return new RouteProgress(0, Double.NaN, Double.NaN);
        int start = Math.max(0, Math.min(startIndex, route.points.size() - 1));
        int bestIndex = start;
        double bestDistance = Double.MAX_VALUE;
        for (int i = start; i < route.points.size(); i++) {
            double distance = distance(location, route.points.get(i));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        double remaining = 0;
        for (int i = bestIndex + 1; i < route.points.size(); i++) {
            remaining += distance(route.points.get(i - 1), route.points.get(i));
        }
        return new RouteProgress(bestIndex, bestDistance, remaining);
    }

    static double nearestDistance(Location location, Route route) {
        if (route == null || route.points.isEmpty()) return Double.NaN;
        double best = Double.MAX_VALUE;
        for (GeoPoint point : route.points) best = Math.min(best, distance(location, point));
        return best;
    }

    static double pathLength(List<GeoPoint> points) {
        double result = 0;
        for (int i = 1; i < points.size(); i++) result += distance(points.get(i - 1), points.get(i));
        return result;
    }

    static double ascent(List<GeoPoint> points) {
        double result = 0;
        for (int i = 1; i < points.size(); i++) {
            double previous = points.get(i - 1).ele;
            double current = points.get(i).ele;
            if (Double.isNaN(previous) || Double.isNaN(current)) continue;
            double gain = current - previous;
            if (gain > 0 && gain < 80) result += gain;
        }
        return result;
    }

    static double distance(Location location, GeoPoint point) {
        float[] output = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(), point.lat, point.lon, output);
        return output[0];
    }

    static double distance(GeoPoint first, GeoPoint second) {
        float[] output = new float[1];
        Location.distanceBetween(first.lat, first.lon, second.lat, second.lon, output);
        return output[0];
    }

    static String formatDistance(double meters) {
        if (Double.isNaN(meters) || Double.isInfinite(meters)) return "—";
        if (meters >= 1000) return String.format(Locale.FRANCE, "%.2f km", meters / 1000.0);
        return Math.round(meters) + " m";
    }
}

final class RouteRepository {
    private static final String PREFS = "route_repository";
    private static final String IDS = "ids";
    private static final String ACTIVE = "active";
    private static final String BUNDLED_ID = "nivolet_officiel";

    private final Context context;
    private final SharedPreferences prefs;

    RouteRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void installBundledNivolet() throws Exception {
        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Collections.emptySet()));
        File file = routeFile(BUNDLED_ID);
        if (ids.contains(BUNDLED_ID) && file.exists()) return;

        try (InputStream input = context.getAssets().open("nivolet.gpx")) {
            ParsedGpx parsed = parseGpx(input);
            save(new Route(BUNDLED_ID, "Croix du Nivolet depuis Le Sire", parsed.points));
        }
    }

    Route importGpx(Uri uri) throws Exception {
        String displayName = queryDisplayName(uri);
        ParsedGpx parsed;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Impossible d'ouvrir le fichier");
            parsed = parseGpx(input);
        }

        String name = parsed.name;
        if (name == null || name.trim().isEmpty()) name = removeExtension(displayName);
        if (name == null || name.trim().isEmpty()) name = "Randonnée importée";
        name = name.trim();

        String id = "route_" + System.currentTimeMillis();
        Route route = new Route(id, name, parsed.points);
        save(route);
        setActiveRouteId(id);
        return route;
    }

    List<Route> listRoutes() {
        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Collections.emptySet()));
        List<Route> routes = new ArrayList<>();
        for (String id : ids) {
            Route route = load(id);
            if (route != null) routes.add(route);
        }
        routes.sort(new Comparator<Route>() {
            @Override
            public int compare(Route first, Route second) {
                if (BUNDLED_ID.equals(first.id)) return -1;
                if (BUNDLED_ID.equals(second.id)) return 1;
                return first.name.compareToIgnoreCase(second.name);
            }
        });
        return routes;
    }

    Route loadActiveRoute() {
        String id = getActiveRouteId();
        Route active = id == null ? null : load(id);
        if (active != null) return active;
        List<Route> all = listRoutes();
        return all.isEmpty() ? null : all.get(0);
    }

    String getActiveRouteId() {
        return prefs.getString(ACTIVE, null);
    }

    void setActiveRouteId(String id) {
        prefs.edit().putString(ACTIVE, id).apply();
    }

    void delete(String id) {
        routeFile(id).delete();
        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Collections.emptySet()));
        ids.remove(id);
        SharedPreferences.Editor editor = prefs.edit().putStringSet(IDS, ids).remove("name_" + id);
        if (id.equals(getActiveRouteId())) editor.remove(ACTIVE);
        editor.apply();
    }

    private void save(Route route) throws IOException {
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(routeFile(route.id)), StandardCharsets.UTF_8))) {
            for (GeoPoint point : route.points) {
                writer.println(point.lat + "," + point.lon + "," + point.ele);
            }
        }

        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Collections.emptySet()));
        ids.add(route.id);
        prefs.edit()
                .putStringSet(IDS, ids)
                .putString("name_" + route.id, route.name)
                .apply();
    }

    private Route load(String id) {
        File file = routeFile(id);
        if (!file.exists()) return null;
        ArrayList<GeoPoint> points = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                points.add(new GeoPoint(
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2])
                ));
            }
        } catch (Exception error) {
            return null;
        }
        if (points.size() < 2) return null;
        return new Route(id, prefs.getString("name_" + id, "Randonnée"), points);
    }

    private File routeFile(String id) {
        File directory = new File(context.getFilesDir(), "routes");
        if (!directory.exists()) directory.mkdirs();
        return new File(directory, id + ".csv");
    }

    private ParsedGpx parseGpx(InputStream input) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(input, null);

        ArrayList<GeoPoint> points = new ArrayList<>();
        String routeName = null;
        boolean insidePoint = false;
        double lat = 0;
        double lon = 0;
        double elevation = Double.NaN;

        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("trkpt".equals(tag) || "rtept".equals(tag)) {
                    String latValue = parser.getAttributeValue(null, "lat");
                    String lonValue = parser.getAttributeValue(null, "lon");
                    if (latValue == null || lonValue == null) continue;
                    lat = Double.parseDouble(latValue);
                    lon = Double.parseDouble(lonValue);
                    elevation = Double.NaN;
                    insidePoint = true;
                } else if (insidePoint && "ele".equals(tag)) {
                    try {
                        elevation = Double.parseDouble(parser.nextText());
                    } catch (Exception ignored) {
                        elevation = Double.NaN;
                    }
                } else if (!insidePoint && routeName == null && "name".equals(tag)) {
                    String candidate = parser.nextText();
                    if (candidate != null && !candidate.trim().isEmpty()) routeName = candidate.trim();
                }
            } else if (event == XmlPullParser.END_TAG && insidePoint
                    && ("trkpt".equals(parser.getName()) || "rtept".equals(parser.getName()))) {
                points.add(new GeoPoint(lat, lon, elevation));
                insidePoint = false;
            }
        }

        if (points.size() < 2) throw new IOException("aucune trace exploitable");
        return new ParsedGpx(routeName, points);
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return "Randonnée importée";
    }

    private String removeExtension(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class ParsedGpx {
        final String name;
        final ArrayList<GeoPoint> points;

        ParsedGpx(String name, ArrayList<GeoPoint> points) {
            this.name = name;
            this.points = points;
        }
    }
}

final class RouteView extends View {
    private Route route;
    private Location current;
    private GeoPoint parking;

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint finishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint parkingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    RouteView(Context context) {
        super(context);
        routePaint.setColor(Color.rgb(25, 112, 72));
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(9f);
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setStrokeJoin(Paint.Join.ROUND);

        currentPaint.setColor(Color.rgb(35, 102, 220));
        startPaint.setColor(Color.rgb(45, 160, 80));
        finishPaint.setColor(Color.rgb(190, 55, 45));
        parkingPaint.setColor(Color.rgb(230, 145, 20));
        textPaint.setColor(Color.rgb(55, 63, 58));
        textPaint.setTextSize(30f);
        gridPaint.setColor(Color.rgb(225, 222, 213));
        gridPaint.setStrokeWidth(1f);
    }

    void setRoute(Route route) {
        this.route = route;
        invalidate();
    }

    void setCurrent(Location current) {
        this.current = current;
        invalidate();
    }

    void setParking(GeoPoint parking) {
        this.parking = parking;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(247, 244, 235));

        for (int i = 1; i < 5; i++) {
            float x = getWidth() * i / 5f;
            float y = getHeight() * i / 5f;
            canvas.drawLine(x, 0, x, getHeight(), gridPaint);
            canvas.drawLine(0, y, getWidth(), y, gridPaint);
        }

        if (route == null || route.points.size() < 2) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Ajoute ou sélectionne un GPX", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }

        double minLat = 90;
        double maxLat = -90;
        double minLon = 180;
        double maxLon = -180;
        for (GeoPoint point : route.points) {
            minLat = Math.min(minLat, point.lat);
            maxLat = Math.max(maxLat, point.lat);
            minLon = Math.min(minLon, point.lon);
            maxLon = Math.max(maxLon, point.lon);
        }

        GeoPoint startPoint = route.points.get(0);
        if (current != null && RouteMath.distance(current, startPoint) < 3000) {
            minLat = Math.min(minLat, current.getLatitude());
            maxLat = Math.max(maxLat, current.getLatitude());
            minLon = Math.min(minLon, current.getLongitude());
            maxLon = Math.max(maxLon, current.getLongitude());
        }
        if (parking != null && RouteMath.distance(parking, startPoint) < 3000) {
            minLat = Math.min(minLat, parking.lat);
            maxLat = Math.max(maxLat, parking.lat);
            minLon = Math.min(minLon, parking.lon);
            maxLon = Math.max(maxLon, parking.lon);
        }

        float padding = 52f;
        double latitudeSpan = Math.max(0.00025, maxLat - minLat);
        double longitudeSpan = Math.max(0.00025, maxLon - minLon);
        double scale = Math.min(
                (getWidth() - 2 * padding) / longitudeSpan,
                (getHeight() - 2 * padding) / latitudeSpan
        );

        Path path = new Path();
        for (int i = 0; i < route.points.size(); i++) {
            GeoPoint point = route.points.get(i);
            float x = x(point.lon, minLon, scale, padding);
            float y = y(point.lat, minLat, scale, padding);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, routePaint);

        GeoPoint finishPoint = route.points.get(route.points.size() - 1);
        canvas.drawCircle(x(startPoint.lon, minLon, scale, padding), y(startPoint.lat, minLat, scale, padding), 14f, startPaint);
        canvas.drawCircle(x(finishPoint.lon, minLon, scale, padding), y(finishPoint.lat, minLat, scale, padding), 17f, finishPaint);

        if (parking != null && RouteMath.distance(parking, startPoint) < 3000) {
            canvas.drawCircle(x(parking.lon, minLon, scale, padding), y(parking.lat, minLat, scale, padding), 12f, parkingPaint);
        }

        if (current != null && RouteMath.distance(current, startPoint) < 3000) {
            float currentX = x(current.getLongitude(), minLon, scale, padding);
            float currentY = y(current.getLatitude(), minLat, scale, padding);
            currentPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(currentX, currentY, 16f, currentPaint);
            currentPaint.setStyle(Paint.Style.STROKE);
            currentPaint.setStrokeWidth(4f);
            canvas.drawCircle(currentX, currentY, 27f, currentPaint);
            currentPaint.setStyle(Paint.Style.FILL);
        }

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(27f);
        canvas.drawText("N ↑", 16f, 33f, textPaint);
        canvas.drawText("Vert : départ   Rouge : arrivée   Orange : parking", 16f, getHeight() - 16f, textPaint);
    }

    private float x(double longitude, double minLongitude, double scale, float padding) {
        return (float) (padding + (longitude - minLongitude) * scale);
    }

    private float y(double latitude, double minLatitude, double scale, float padding) {
        return (float) (getHeight() - padding - (latitude - minLatitude) * scale);
    }
}
