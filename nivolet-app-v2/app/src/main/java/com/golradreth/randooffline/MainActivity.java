package com.golradreth.randooffline;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.ScaleBarOverlay;

import java.io.File;
import java.util.*;

public class MainActivity extends Activity {
    private static final int PICK_GPX = 40;
    private static final int ASK_PERMISSIONS = 41;
    private static final int OFF_ROUTE_METERS = 80;

    private static final OnlineTileSourceBase PLAN_IGN = new OnlineTileSourceBase(
            "Plan IGN", 5, 19, 256, ".png",
            new String[]{"https://data.geopf.fr/wmts?"}, "© IGN") {
        @Override public String getTileURLString(long index) {
            int z = MapTileIndex.getZoom(index);
            int x = MapTileIndex.getX(index);
            int y = MapTileIndex.getY(index);
            return getBaseUrl()
                    + "SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
                    + "&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2"
                    + "&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM"
                    + "&TILEMATRIX=" + z + "&TILEROW=" + y + "&TILECOL=" + x;
        }
    };

    private final List<Route> routes = new ArrayList<>();
    private RouteRepository repository;
    private Route activeRoute;

    private Spinner spinner;
    private TextView routeInfo;
    private TextView liveInfo;
    private TextView status;
    private TextView attribution;
    private MapView map;
    private Button trackingButton;
    private Button sourceButton;
    private Button networkButton;

    private Polyline routeLine;
    private Marker startMarker;
    private Marker finishMarker;
    private Marker currentMarker;
    private Marker parkingMarker;

    private Location lastLocation;
    private GeoPoint parking;
    private boolean tracking;
    private boolean receiverRegistered;
    private boolean follow = true;
    private boolean offline;
    private boolean ign = true;
    private double walked;
    private long elapsed;
    private int routeIndex;

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Location location = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(HikeService.EXTRA_LOCATION, Location.class)
                    : intent.getParcelableExtra(HikeService.EXTRA_LOCATION);
            if (location == null) return;
            lastLocation = location;
            walked = intent.getDoubleExtra(HikeService.EXTRA_WALKED, walked);
            elapsed = intent.getLongExtra(HikeService.EXTRA_ELAPSED, elapsed);
            updateCurrentMarker();
            refreshStats();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureMap();

        repository = new RouteRepository(this);
        try {
            repository.installBundledNivolet();
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de charger le GPX du Nivolet.", Toast.LENGTH_LONG).show();
        }

        parking = loadParking();
        tracking = getSharedPreferences(HikeService.SESSION_PREFS, MODE_PRIVATE)
                .getBoolean(HikeService.SESSION_TRACKING, false);
        buildUi();
        reloadRoutes(repository.getActiveRouteId());
        updateTrackingButton();
    }

    private void configureMap() {
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        File base = new File(root, "osmdroid");
        File tiles = new File(base, "tiles");
        base.mkdirs();
        tiles.mkdirs();

        IConfigurationProvider config = Configuration.getInstance();
        config.setUserAgentValue(getPackageName() + "/3.0");
        config.setOsmdroidBasePath(base);
        config.setOsmdroidTileCache(tiles);
        config.setTileFileSystemCacheMaxBytes(300L * 1024 * 1024);
        config.setTileFileSystemCacheTrimBytes(240L * 1024 * 1024);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 247, 244));

        TextView title = text("Rando Savoie", 22, true);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.rgb(24, 66, 45));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(13), dp(16), dp(13));
        root.addView(title);

        spinner = new Spinner(this);
        spinner.setPadding(dp(10), dp(2), dp(10), dp(2));
        root.addView(spinner);

        routeInfo = text("", 14, false);
        routeInfo.setPadding(dp(12), 0, dp(12), dp(2));
        root.addView(routeInfo);

        status = text("Prêt", 14, true);
        status.setPadding(dp(12), dp(2), dp(12), dp(3));
        root.addView(status);

        map = new MapView(this);
        map.setTileSource(PLAN_IGN);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.setTilesScaledToDpi(true);
        map.setUseDataConnection(true);
        map.getController().setZoom(15.0);
        map.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) follow = false;
            return false;
        });

        ScaleBarOverlay scale = new ScaleBarOverlay(map);
        scale.setCentred(true);
        scale.setScaleBarOffset(dp(10), dp(14));
        map.getOverlays().add(scale);

        root.addView(map, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        attribution = text("Fond : Plan IGN — © IGN", 11, false);
        attribution.setGravity(Gravity.END);
        attribution.setPadding(dp(8), 0, dp(8), 0);
        root.addView(attribution);

        liveInfo = text("GPS non démarré", 14, false);
        liveInfo.setPadding(dp(12), dp(4), dp(12), dp(4));
        root.addView(liveInfo);

        trackingButton = button("Démarrer");
        Button centerButton = button("Me centrer");
        networkButton = button("En ligne");
        root.addView(row(trackingButton, centerButton, networkButton));

        Button importButton = button("Ajouter GPX");
        Button deleteButton = button("Supprimer");
        sourceButton = button("Fond OSM");
        root.addView(row(importButton, deleteButton, sourceButton));

        Button parkingButton = button("Parking");
        Button shareButton = button("Partager");
        Button gpsButton = button("Réglages GPS");
        root.addView(row(parkingButton, shareButton, gpsButton));

        setContentView(root);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < routes.size()) selectRoute(routes.get(pos));
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        trackingButton.setOnClickListener(v -> toggleTracking());
        centerButton.setOnClickListener(v -> center());
        networkButton.setOnClickListener(v -> toggleNetwork());
        importButton.setOnClickListener(v -> pickGpx());
        deleteButton.setOnClickListener(v -> deleteRoute());
        sourceButton.setOnClickListener(v -> toggleSource());
        parkingButton.setOnClickListener(v -> markParking());
        shareButton.setOnClickListener(v -> sharePosition());
        gpsButton.setOnClickListener(v -> openGpsSettings());
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(3), 0, dp(3), dp(2));
        for (Button button : buttons) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(dp(2), 0, dp(2), 0);
            row.addView(button, p);
        }
        return row;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(36, 43, 39));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinHeight(dp(46));
        button.setPadding(dp(3), 0, dp(3), 0);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void pickGpx() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_GPX);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK_GPX || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Route route = repository.importGpx(data.getData());
            reloadRoutes(route.id);
            setStatus("Randonnée ajoutée : " + route.name, false);
        } catch (Exception e) {
            Toast.makeText(this, "GPX invalide : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void reloadRoutes(String selectedId) {
        routes.clear();
        routes.addAll(repository.listRoutes());
        List<String> labels = new ArrayList<>();
        for (Route r : routes) labels.add(r.name);
        if (labels.isEmpty()) labels.add("Aucune randonnée");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        if (routes.isEmpty()) {
            activeRoute = null;
            clearRouteOverlays();
            routeInfo.setText("Ajoute un fichier GPX.");
            refreshStats();
            return;
        }

        int index = 0;
        for (int i = 0; selectedId != null && i < routes.size(); i++) {
            if (selectedId.equals(routes.get(i).id)) index = i;
        }
        spinner.setSelection(index);
        selectRoute(routes.get(index));
    }

    private void selectRoute(Route route) {
        if (tracking && activeRoute != null && !activeRoute.id.equals(route.id)) stopTracking();
        activeRoute = route;
        routeIndex = 0;
        repository.setActiveRouteId(route.id);
        drawRoute();
        routeInfo.setText(RouteMath.formatDistance(route.lengthMeters)
                + " • D+ " + Math.round(route.ascentMeters) + " m"
                + " • " + route.points.size() + " points");
        if (!tracking) setStatus("Randonnée prête", false);
        refreshStats();
    }

    private void drawRoute() {
        clearRouteOverlays();
        if (activeRoute == null || activeRoute.points.size() < 2) return;

        ArrayList<org.osmdroid.util.GeoPoint> points = new ArrayList<>();
        for (GeoPoint p : activeRoute.points) {
            points.add(new org.osmdroid.util.GeoPoint(p.lat, p.lon));
        }

        routeLine = new Polyline();
        routeLine.setPoints(points);
        routeLine.setColor(Color.rgb(215, 42, 32));
        routeLine.setWidth(dp(5));
        map.getOverlays().add(routeLine);

        startMarker = marker(points.get(0), "Départ");
        finishMarker = marker(points.get(points.size() - 1), "Arrivée");
        map.getOverlays().add(startMarker);
        map.getOverlays().add(finishMarker);
        updateParkingMarker();
        updateCurrentMarker();
        map.invalidate();

        BoundingBox box = BoundingBox.fromGeoPoints(points);
        map.post(() -> map.zoomToBoundingBox(box, true, dp(52)));
    }

    private Marker marker(org.osmdroid.util.GeoPoint point, String title) {
        Marker marker = new Marker(map);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(title);
        return marker;
    }

    private void clearRouteOverlays() {
        if (map == null) return;
        if (routeLine != null) map.getOverlays().remove(routeLine);
        if (startMarker != null) map.getOverlays().remove(startMarker);
        if (finishMarker != null) map.getOverlays().remove(finishMarker);
        if (currentMarker != null) map.getOverlays().remove(currentMarker);
        if (parkingMarker != null) map.getOverlays().remove(parkingMarker);
        routeLine = null;
        startMarker = finishMarker = currentMarker = parkingMarker = null;
        map.invalidate();
    }

    private void updateCurrentMarker() {
        if (map == null || lastLocation == null) return;
        org.osmdroid.util.GeoPoint p = new org.osmdroid.util.GeoPoint(
                lastLocation.getLatitude(), lastLocation.getLongitude());
        if (currentMarker == null) {
            currentMarker = marker(p, "Ma position");
            map.getOverlays().add(currentMarker);
        } else {
            currentMarker.setPosition(p);
        }
        currentMarker.setSnippet("Précision ±" + Math.round(lastLocation.getAccuracy()) + " m");
        if (follow && tracking) map.getController().animateTo(p);
        map.invalidate();
    }

    private void updateParkingMarker() {
        if (parkingMarker != null) map.getOverlays().remove(parkingMarker);
        parkingMarker = null;
        if (parking == null) return;
        parkingMarker = marker(
                new org.osmdroid.util.GeoPoint(parking.lat, parking.lon), "Parking");
        map.getOverlays().add(parkingMarker);
    }

    private void center() {
        follow = true;
        if (lastLocation != null) {
            map.getController().animateTo(new org.osmdroid.util.GeoPoint(
                    lastLocation.getLatitude(), lastLocation.getLongitude()));
            if (map.getZoomLevelDouble() < 16) map.getController().setZoom(16.0);
            setStatus("Centré sur ta position", false);
        } else if (activeRoute != null) {
            drawRoute();
            setStatus("Centré sur la randonnée", false);
        } else {
            setStatus("Position indisponible", true);
        }
    }

    private void toggleSource() {
        ign = !ign;
        map.setTileSource(ign ? PLAN_IGN : TileSourceFactory.MAPNIK);
        sourceButton.setText(ign ? "Fond OSM" : "Fond IGN");
        attribution.setText(ign
                ? "Fond : Plan IGN — © IGN"
                : "Fond : OpenStreetMap — © contributeurs OSM");
        setStatus(ign ? "Fond Plan IGN activé" : "Fond OpenStreetMap activé", false);
        map.invalidate();
    }

    private void toggleNetwork() {
        offline = !offline;
        map.setUseDataConnection(!offline);
        networkButton.setText(offline ? "Hors ligne" : "En ligne");
        setStatus(offline
                ? "Hors ligne : affichage des zones déjà consultées"
                : "En ligne : les cartes consultées sont mises en cache", false);
    }

    private void deleteRoute() {
        if (activeRoute == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Supprimer la randonnée ?")
                .setMessage(activeRoute.name)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (d, w) -> {
                    if (tracking) stopTracking();
                    repository.delete(activeRoute.id);
                    reloadRoutes(null);
                }).show();
    }

    private void toggleTracking() {
        if (tracking) {
            stopTracking();
            return;
        }
        if (activeRoute == null) {
            Toast.makeText(this, "Sélectionne une randonnée.", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), ASK_PERMISSIONS);
        } else {
            startTracking();
        }
    }

    private void startTracking() {
        tracking = true;
        follow = true;
        walked = 0;
        elapsed = 0;
        routeIndex = 0;
        updateTrackingButton();
        setStatus("Recherche du signal GPS…", false);
        Intent service = new Intent(this, HikeService.class);
        service.putExtra(HikeService.EXTRA_ROUTE_ID, activeRoute.id);
        startForegroundService(service);
    }

    private void stopTracking() {
        tracking = false;
        stopService(new Intent(this, HikeService.class));
        updateTrackingButton();
        setStatus("Suivi arrêté", false);
        refreshStats();
    }

    private void updateTrackingButton() {
        if (trackingButton != null) trackingButton.setText(tracking ? "Arrêter" : "Démarrer");
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] results) {
        super.onRequestPermissionsResult(req, p, results);
        if (req != ASK_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        } else {
            setStatus("Autorisation GPS refusée", true);
        }
    }

    private void refreshStats() {
        if (activeRoute == null) {
            liveInfo.setText("Aucune randonnée sélectionnée");
            return;
        }

        String remaining = "—";
        String off = "—";
        String altitude = "—";
        String accuracy = "—";
        String parkingDistance = "—";

        if (lastLocation != null) {
            RouteProgress progress = RouteMath.progress(
                    lastLocation, activeRoute, tracking ? Math.max(0, routeIndex - 30) : 0);
            if (tracking) routeIndex = Math.max(routeIndex, progress.index);
            remaining = RouteMath.formatDistance(progress.remainingMeters);
            off = RouteMath.formatDistance(progress.offRouteMeters);
            altitude = lastLocation.hasAltitude()
                    ? Math.round(lastLocation.getAltitude()) + " m" : "—";
            accuracy = "±" + Math.round(lastLocation.getAccuracy()) + " m";
            if (parking != null) {
                parkingDistance = RouteMath.formatDistance(
                        RouteMath.distance(lastLocation, parking));
            }
            if (progress.offRouteMeters > OFF_ROUTE_METERS) {
                setStatus("⚠ Hors tracé d'environ " + Math.round(progress.offRouteMeters) + " m", true);
            } else if (tracking) {
                setStatus("Sur le tracé — GPS actif", false);
            }
        }

        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        liveInfo.setText("Parcouru " + RouteMath.formatDistance(walked)
                + " • Reste " + remaining + " • Écart " + off
                + "\nAltitude " + altitude + " • GPS " + accuracy
                + " • Temps " + formatElapsed(elapsed)
                + " • Batterie " + (battery < 0 ? "—" : battery + " %")
                + "\nParking " + parkingDistance);
    }

    private String formatElapsed(long millis) {
        long minutes = millis / 60_000L;
        return String.format(Locale.FRANCE, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void markParking() {
        if (lastLocation != null) {
            parking = new GeoPoint(lastLocation.getLatitude(), lastLocation.getLongitude(),
                    lastLocation.hasAltitude() ? lastLocation.getAltitude() : Double.NaN);
        } else if (activeRoute != null && !activeRoute.points.isEmpty()) {
            parking = activeRoute.points.get(0);
        } else {
            Toast.makeText(this, "Position indisponible.", Toast.LENGTH_SHORT).show();
            return;
        }
        saveParking();
        updateParkingMarker();
        map.invalidate();
        setStatus("Parking mémorisé", false);
        refreshStats();
    }

    private void sharePosition() {
        GeoPoint point = lastLocation == null ? parking : new GeoPoint(
                lastLocation.getLatitude(), lastLocation.getLongitude(), Double.NaN);
        if (point == null) {
            Toast.makeText(this, "Position indisponible.", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = "https://www.openstreetmap.org/?mlat=" + point.lat
                + "&mlon=" + point.lon + "#map=16/" + point.lat + "/" + point.lon;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, "Ma position : " + url);
        startActivity(Intent.createChooser(intent, "Partager ma position"));
    }

    private void openGpsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void setStatus(String value, boolean warning) {
        status.setText(value);
        status.setTextColor(warning ? Color.rgb(180, 35, 28) : Color.rgb(24, 66, 45));
    }

    private void saveParking() {
        getSharedPreferences("parking", MODE_PRIVATE).edit()
                .putLong("lat", Double.doubleToRawLongBits(parking.lat))
                .putLong("lon", Double.doubleToRawLongBits(parking.lon))
                .putBoolean("saved", true).apply();
    }

    private GeoPoint loadParking() {
        SharedPreferences p = getSharedPreferences("parking", MODE_PRIVATE);
        if (!p.getBoolean("saved", false)) return null;
        return new GeoPoint(
                Double.longBitsToDouble(p.getLong("lat", 0)),
                Double.longBitsToDouble(p.getLong("lon", 0)), Double.NaN);
    }

    @Override protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        tracking = getSharedPreferences(HikeService.SESSION_PREFS, MODE_PRIVATE)
                .getBoolean(HikeService.SESSION_TRACKING, false);
        updateTrackingButton();
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

    @Override protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(locationReceiver);
            receiverRegistered = false;
        }
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
