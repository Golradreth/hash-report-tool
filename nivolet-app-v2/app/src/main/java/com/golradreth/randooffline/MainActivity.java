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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
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

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.MapTileIndex;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CompassOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.ScaleBarOverlay;
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_GPX = 40;
    private static final int REQUEST_PERMISSIONS = 41;
    private static final int OFF_ROUTE_WARNING_METERS = 80;

    private static final OnlineTileSourceBase PLAN_IGN = new OnlineTileSourceBase(
            "Plan IGN", 5, 19, 256, ".png",
            new String[]{"https://data.geopf.fr/wmts?"},
            "© IGN"
    ) {
        @Override
        public String getTileURLString(long tileIndex) {
            int zoom = MapTileIndex.getZoom(tileIndex);
            int x = MapTileIndex.getX(tileIndex);
            int y = MapTileIndex.getY(tileIndex);
            return getBaseUrl()
                    + "SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
                    + "&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2"
                    + "&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM"
                    + "&TILEMATRIX=" + zoom
                    + "&TILEROW=" + y
                    + "&TILECOL=" + x;
        }
    };

    private final List<Route> routes = new ArrayList<>();
    private RouteRepository repository;
    private Route activeRoute;

    private Spinner routeSpinner;
    private TextView routeInfo;
    private TextView liveInfo;
    private TextView status;
    private TextView attribution;
    private MapView map;
    private Button trackingButton;
    private Button mapSourceButton;
    private Button offlineButton;

    private Polyline routeOverlay;
    private Marker startMarker;
    private Marker finishMarker;
    private Marker currentMarker;
    private Marker parkingMarker;

    private Location lastLocation;
    private GeoPoint parkingPoint;
    private boolean tracking;
    private boolean receiverRegistered;
    private boolean followLocation = true;
    private boolean offlineMode;
    private boolean useIgn = true;
    private double walkedMeters;
    private long elapsedMillis;
    private int lastRouteIndex;

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
            updateCurrentMarker();
            refreshLiveData();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        configureMapCache();
        repository = new RouteRepository(this);
        try {
            repository.installBundledNivolet();
        } catch (Exception error) {
            Toast.makeText(this, "Le GPX du Nivolet n'a pas pu être préchargé.", Toast.LENGTH_LONG).show();
        }

        parkingPoint = loadParking();
        tracking = getSharedPreferences(HikeService.SESSION_PREFS, MODE_PRIVATE)
                .getBoolean(HikeService.SESSION_TRACKING, false);

        buildInterface();
        reloadRoutes(repository.getActiveRouteId());
        updateTrackingButton();
    }

    private void configureMapCache() {
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        File base = new File(root, "osmdroid");
        File tiles = new File(base, "tiles");
        base.mkdirs();
        tiles.mkdirs();

        Configuration configuration = Configuration.getInstance();
        configuration.setUserAgentValue(getPackageName() + "/3.0");
        configuration.setOsmdroidBasePath(base);
        configuration.setOsmdroidTileCache(tiles);
        configuration.setTileFileSystemCacheMaxBytes(300L * 1024L * 1024L);
        configuration.setTileFileSystemCacheTrimBytes(240L * 1024L * 1024L);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 247, 244));

        TextView title = text("Rando Savoie", 22, true);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.rgb(24, 66, 45));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(13), dp(16), dp(13));
        root.addView(title);

        routeSpinner = new Spinner(this);
        routeSpinner.setPadding(dp(10), dp(2), dp(10), dp(2));
        root.addView(routeSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        routeInfo = text("", 14, false);
        routeInfo.setPadding(dp(12), 0, dp(12), dp(3));
        root.addView(routeInfo);

        status = text("Prêt", 14, true);
        status.setTextColor(Color.rgb(24, 66, 45));
        status.setPadding(dp(12), dp(3), dp(12), dp(4));
        root.addView(status);

        map = new MapView(this);
        map.setTileSource(PLAN_IGN);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.setTilesScaledToDpi(true);
        map.setUseDataConnection(true);
        map.getController().setZoom(15.0);
        map.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) followLocation = false;
            return false;
        });

        ScaleBarOverlay scaleBar = new ScaleBarOverlay(map);
        scaleBar.setCentred(true);
        scaleBar.setScaleBarOffset(dp(10), dp(14));
        map.getOverlays().add(scaleBar);

        CompassOverlay compass = new CompassOverlay(
                this,
                new InternalCompassOrientationProvider(this),
                map
        );
        compass.enableCompass();
        map.getOverlays().add(compass);

        root.addView(map, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        attribution = text("Fond : Plan IGN — © IGN", 11, false);
        attribution.setGravity(Gravity.END);
        attribution.setPadding(dp(8), dp(1), dp(8), dp(1));
        root.addView(attribution);

        liveInfo = text("GPS non démarré", 14, false);
        liveInfo.setPadding(dp(12), dp(5), dp(12), dp(5));
        root.addView(liveInfo);

        LinearLayout firstRow = horizontalRow();
        trackingButton = button("Démarrer");
        Button centerButton = button("Me centrer");
        offlineButton = button("En ligne");
        firstRow.addView(trackingButton, weighted());
        firstRow.addView(centerButton, weighted());
        firstRow.addView(offlineButton, weighted());
        root.addView(firstRow);

        LinearLayout secondRow = horizontalRow();
        Button importButton = button("Ajouter GPX");
        Button deleteButton = button("Supprimer");
        mapSourceButton = button("Fond OSM");
        secondRow.addView(importButton, weighted());
        secondRow.addView(deleteButton, weighted());
        secondRow.addView(mapSourceButton, weighted());
        root.addView(secondRow);

        LinearLayout thirdRow = horizontalRow();
        Button parkingButton = button("Parking");
        Button shareButton = button("Partager");
        Button settingsButton = button("Réglages GPS");
        thirdRow.addView(parkingButton, weighted());
        thirdRow.addView(shareButton, weighted());
        thirdRow.addView(settingsButton, weighted());
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

        trackingButton.setOnClickListener(view -> toggleTracking());
        centerButton.setOnClickListener(view -> centerOnCurrentOrRoute());
        offlineButton.setOnClickListener(view -> toggleOfflineMode());
        importButton.setOnClickListener(view -> openGpxPicker());
        deleteButton.setOnClickListener(view -> deleteActiveRoute());
        mapSourceButton.setOnClickListener(view -> toggleMapSource());
        parkingButton.setOnClickListener(view -> markParking());
        shareButton.setOnClickListener(view -> sharePosition());
        settingsButton.setOnClickListener(view -> openLocationSettings());
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
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
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(3), 0, dp(3), dp(2));
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
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
        if (requestCode != REQUEST_GPX || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;

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

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        routeSpinner.setAdapter(adapter);

        if (routes.isEmpty()) {
            activeRoute = null;
            clearRouteOverlays();
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
        if (route == null) return;
        if (tracking && activeRoute != null && !activeRoute.id.equals(route.id)) stopTracking();

        activeRoute = route;
        lastRouteIndex = 0;
        repository.setActiveRouteId(route.id);
        drawRoute(route);

        routeInfo.setText(
                RouteMath.formatDistance(route.lengthMeters)
                        + "  •  D+ " + Math.round(route.ascentMeters) + " m"
                        + "  •  " + route.points.size() + " points"
        );
        if (!tracking) status("Randonnée prête", false);
        refreshLiveData();
    }

    private void drawRoute(Route route) {
        clearRouteOverlays();

        ArrayList<org.osmdroid.util.GeoPoint> mapPoints = new ArrayList<>();
        for (GeoPoint point : route.points) {
            mapPoints.add(new org.osmdroid.util.GeoPoint(point.lat, point.lon));
        }

        routeOverlay = new Polyline();
        routeOverlay.setPoints(mapPoints);
        routeOverlay.setColor(Color.rgb(211, 45, 35));
        routeOverlay.setWidth(dp(5));
        map.getOverlays().add(routeOverlay);

        startMarker = marker(
                mapPoints.get(0),
                "Départ",
                android.R.drawable.ic_media_play
        );
        finishMarker = marker(
                mapPoints.get(mapPoints.size() - 1),
                "Arrivée",
                android.R.drawable.star_big_on
        );
        map.getOverlays().add(startMarker);
        map.getOverlays().add(finishMarker);

        updateParkingMarker();
        updateCurrentMarker();
        map.invalidate();

        BoundingBox boundingBox = BoundingBox.fromGeoPoints(mapPoints);
        map.post(() -> map.zoomToBoundingBox(boundingBox, true, dp(52)));
    }

    private Marker marker(org.osmdroid.util.GeoPoint point, String title, int iconResource) {
        Marker marker = new Marker(map);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(title);
        Drawable icon = getDrawable(iconResource);
        if (icon != null) marker.setIcon(icon);
        return marker;
    }

    private void clearRouteOverlays() {
        if (routeOverlay != null) map.getOverlays().remove(routeOverlay);
        if (startMarker != null) map.getOverlays().remove(startMarker);
        if (finishMarker != null) map.getOverlays().remove(finishMarker);
        if (currentMarker != null) map.getOverlays().remove(currentMarker);
        if (parkingMarker != null) map.getOverlays().remove(parkingMarker);
        routeOverlay = null;
        startMarker = null;
        finishMarker = null;
        currentMarker = null;
        parkingMarker = null;
        map.invalidate();
    }

    private void updateCurrentMarker() {
        if (map == null || lastLocation == null) return;
        org.osmdroid.util.GeoPoint point = new org.osmdroid.util.GeoPoint(
                lastLocation.getLatitude(), lastLocation.getLongitude()
        );
        if (currentMarker == null) {
            currentMarker = marker(point, "Ma position", android.R.drawable.presence_online);
            map.getOverlays().add(currentMarker);
        } else {
            currentMarker.setPosition(point);
        }
        currentMarker.setSnippet("Précision ±" + Math.round(lastLocation.getAccuracy()) + " m");
        if (followLocation && tracking) map.getController().animateTo(point);
        map.invalidate();
    }

    private void updateParkingMarker() {
        if (parkingMarker != null) map.getOverlays().remove(parkingMarker);
        parkingMarker = null;
        if (parkingPoint == null) return;

        parkingMarker = marker(
                new org.osmdroid.util.GeoPoint(parkingPoint.lat, parkingPoint.lon),
                "Parking",
                android.R.drawable.ic_menu_mylocation
        );
        map.getOverlays().add(parkingMarker);
    }

    private void centerOnCurrentOrRoute() {
        followLocation = true;
        if (lastLocation != null) {
            map.getController().animateTo(new org.osmdroid.util.GeoPoint(
                    lastLocation.getLatitude(), lastLocation.getLongitude()
            ));
            if (map.getZoomLevelDouble() < 16) map.getController().setZoom(16.0);
            status("Carte centrée sur ta position", false);
        } else if (activeRoute != null) {
            drawRoute(activeRoute);
            status("Carte centrée sur la randonnée", false);
        } else {
            status("Aucune position ni randonnée disponible", true);
        }
    }

    private void toggleMapSource() {
        useIgn = !useIgn;
        if (useIgn) {
            map.setTileSource(PLAN_IGN);
            mapSourceButton.setText("Fond OSM");
            attribution.setText("Fond : Plan IGN — © IGN");
            status("Fond Plan IGN activé", false);
        } else {
            map.setTileSource(TileSourceFactory.MAPNIK);
            mapSourceButton.setText("Fond IGN");
            attribution.setText("Fond : OpenStreetMap — © contributeurs OSM");
            status("Fond OpenStreetMap activé", false);
        }
        map.invalidate();
    }

    private void toggleOfflineMode() {
        offlineMode = !offlineMode;
        map.setUseDataConnection(!offlineMode);
        offlineButton.setText(offlineMode ? "Hors ligne" : "En ligne");
        if (offlineMode) {
            status("Mode hors ligne : seules les zones déjà affichées restent visibles", false);
        } else {
            status("Connexion carte activée — les tuiles consultées sont mises en cache", false);
        }
    }

    private void deleteActiveRoute() {
        if (activeRoute == null) return;
        String routeName = activeRoute.name;
        new AlertDialog.Builder(this)
                .setTitle("Supprimer la randonnée ?")
                .setMessage(routeName + " sera retirée de l'application.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (dialog, which) -> {
                    if (tracking) stopTracking();
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
            Toast.makeText(this, "Sélectionne d'abord une randonnée.", Toast.LENGTH_SHORT).show();
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
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startTracking();
    }

    private void startTracking() {
        try {
            tracking = true;
            followLocation = true;
            walkedMeters = 0;
            elapsedMillis = 0;
            lastRouteIndex = 0;
            updateTrackingButton();
            status("Recherche du signal GPS…", false);

            Intent service = new Intent(this, HikeService.class);
            service.putExtra(HikeService.EXTRA_ROUTE_ID, activeRoute.id);
            startForegroundService(service);
        } catch (Exception error) {
            tracking = false;
            updateTrackingButton();
            status("Le suivi GPS n'a pas pu démarrer", true);
        }
    }

    private void stopTracking() {
        tracking = false;
        stopService(new Intent(this, HikeService.class));
        updateTrackingButton();
        status("Suivi arrêté", false);
        refreshLiveData();
    }

    private void updateTrackingButton() {
        if (trackingButton != null) {
            trackingButton.setText(tracking ? "Arrêter" : "Démarrer");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
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
                ? "—"
                : RouteMath.formatDistance(RouteMath.distance(lastLocation, parkingPoint));

        if (lastLocation != null) {
            altitude = lastLocation.hasAltitude()
                    ? Math.round(lastLocation.getAltitude()) + " m"
                    : "—";
            accuracy = "±" + Math.round(lastLocation.getAccuracy()) + " m";

            RouteProgress progress = RouteMath.progress(
                    lastLocation,
                    activeRoute,
                    tracking ? Math.max(0, lastRouteIndex - 30) : 0
            );
            if (tracking) lastRouteIndex = Math.max(lastRouteIndex, progress.index);
            remaining = RouteMath.formatDistance(progress.remainingMeters);
            offRoute = RouteMath.formatDistance(progress.offRouteMeters);

            if (progress.offRouteMeters > OFF_ROUTE_WARNING_METERS) {
                status("⚠ Hors tracé d'environ " + Math.round(progress.offRouteMeters) + " m", true);
            } else if (tracking) {
                status("Sur le tracé — suivi GPS actif", false);
            }
        }

        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = batteryManager == null
                ? -1
                : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        liveInfo.setText(
                "Parcouru " + RouteMath.formatDistance(walkedMeters)
                        + "   •   Reste " + remaining
                        + "   •   Écart " + offRoute
                        + "\nAltitude " + altitude
                        + "   •   GPS " + accuracy
                        + "   •   Temps " + formatElapsed(elapsedMillis)
                        + "   •   Batterie " + (battery < 0 ? "—" : battery + " %")
                        + "\nParking " + parkingDistance
        );
    }

    private String formatElapsed(long millis) {
        long totalMinutes = millis / 60_000L;
        return String.format(
                Locale.FRANCE, "%02d:%02d", totalMinutes / 60, totalMinutes % 60
        );
    }

    private void markParking() {
        if (lastLocation != null) {
            parkingPoint = new GeoPoint(
                    lastLocation.getLatitude(),
                    lastLocation.getLongitude(),
                    lastLocation.hasAltitude() ? lastLocation.getAltitude() : Double.NaN
            );
        } else if (activeRoute != null && !activeRoute.points.isEmpty()) {
            parkingPoint = activeRoute.points.get(0);
        } else {
            Toast.makeText(this, "Position indisponible.", Toast.LENGTH_SHORT).show();
            return;
        }

        saveParking(parkingPoint);
        updateParkingMarker();
        map.invalidate();
        status("Parking mémorisé", false);
        refreshLiveData();
    }

    private void sharePosition() {
        GeoPoint point = null;
        if (lastLocation != null) {
            point = new GeoPoint(
                    lastLocation.getLatitude(),
                    lastLocation.getLongitude(),
                    lastLocation.hasAltitude() ? lastLocation.getAltitude() : Double.NaN
            );
        } else if (parkingPoint != null) {
            point = parkingPoint;
        }

        if (point == null) {
            Toast.makeText(this, "Aucune position disponible.", Toast.LENGTH_SHORT).show();
            return;
        }

        String message = "Ma position : https://www.openstreetmap.org/?mlat="
                + point.lat + "&mlon=" + point.lon
                + "#map=16/" + point.lat + "/" + point.lon;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, message);
        startActivity(Intent.createChooser(share, "Partager ma position"));
    }

    private void openLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void status(String message, boolean warning) {
        status.setText(message);
        status.setTextColor(
                warning ? Color.rgb(180, 35, 28) : Color.rgb(24, 66, 45)
        );
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

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(locationReceiver);
            receiverRegistered = false;
        }
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (map != null) map.onDetach();
        super.onDestroy();
    }
}
