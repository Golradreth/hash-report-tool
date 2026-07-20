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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.ScaleBarOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int PICK_GPX = 40;
    private static final int ASK_PERMISSIONS = 41;
    private static final int OFF_ROUTE_METERS = 80;

    private static final int FOREST = Color.rgb(24, 57, 43);
    private static final int FOREST_SOFT = Color.rgb(43, 88, 66);
    private static final int ACCENT = Color.rgb(244, 92, 45);
    private static final int WALKED = Color.rgb(31, 103, 219);
    private static final int WARNING = Color.rgb(190, 45, 38);
    private static final int TEXT = Color.rgb(35, 48, 41);
    private static final int CARD = Color.argb(244, 255, 255, 255);

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

    private static final OnlineTileSourceBase OPEN_TOPO = new OnlineTileSourceBase(
            "OpenTopoMap", 0, 17, 256, ".png",
            new String[]{
                    "https://a.tile.opentopomap.org/",
                    "https://b.tile.opentopomap.org/",
                    "https://c.tile.opentopomap.org/"
            }, "© OpenStreetMap contributors • OpenTopoMap") {
        @Override public String getTileURLString(long index) {
            int z = MapTileIndex.getZoom(index);
            int x = MapTileIndex.getX(index);
            int y = MapTileIndex.getY(index);
            return getBaseUrl() + z + "/" + x + "/" + y + ".png";
        }
    };

    private final List<Route> routes = new ArrayList<>();
    private final ArrayList<GeoPoint> walkedPoints = new ArrayList<>();
    private RouteRepository repository;
    private Route activeRoute;

    private MapView map;
    private TextView routeTitle;
    private TextView routeMeta;
    private TextView statusChip;
    private TextView attribution;
    private TextView remainingValue;
    private TextView timeValue;
    private TextView altitudeValue;
    private TextView offRouteValue;
    private TextView detailsValue;
    private LinearLayout detailsPanel;
    private TextView trackingButton;

    private Polyline routeLine;
    private Polyline walkedLine;
    private Marker startMarker;
    private Marker finishMarker;
    private Marker parkingMarker;
    private DirectionOverlay directionOverlay;

    private Location lastLocation;
    private GeoPoint parking;
    private boolean sessionExists;
    private boolean tracking;
    private boolean receiverRegistered;
    private boolean follow = true;
    private boolean firstFixCentered;
    private boolean offline;
    private boolean ign = true;
    private boolean detailsExpanded;
    private double walked;
    private long elapsed;
    private int routeIndex;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float compassHeading = Float.NaN;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiTicker = new Runnable() {
        @Override public void run() {
            if (tracking) {
                elapsed += 1000L;
                refreshStats();
            }
            uiHandler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Location location = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(HikeService.EXTRA_LOCATION, Location.class)
                    : intent.getParcelableExtra(HikeService.EXTRA_LOCATION);
            if (location == null) return;
            lastLocation = location;
            sessionExists = true;
            tracking = true;
            walked = intent.getDoubleExtra(HikeService.EXTRA_WALKED, walked);
            elapsed = intent.getLongExtra(HikeService.EXTRA_ELAPSED, elapsed);
            routeIndex = intent.getIntExtra(HikeService.EXTRA_ROUTE_INDEX, routeIndex);
            appendWalkedPoint(location);
            updateDirectionOverlay();
            updateTrackingButton();
            refreshStats();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(FOREST);
        getWindow().setNavigationBarColor(Color.rgb(245, 247, 244));
        configureMap();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        repository = new RouteRepository(this);
        try {
            repository.installBundledNivolet();
        } catch (Exception error) {
            Toast.makeText(this, "Impossible de charger le GPX du Nivolet.", Toast.LENGTH_LONG).show();
        }

        parking = loadParking();
        loadSessionState();
        walkedPoints.addAll(HikeService.loadTrack(this));
        buildUi();
        SharedPreferences prefs = HikeService.sessionPrefs(this);
        String selected = sessionExists ? prefs.getString(HikeService.SESSION_ROUTE_ID, null)
                : repository.getActiveRouteId();
        reloadRoutes(selected);
        updateTrackingButton();
        refreshStats();
    }

    private void configureMap() {
        File root = getExternalFilesDir(null);
        if (root == null) root = getFilesDir();
        File base = new File(root, "osmdroid-v5");
        File tiles = new File(base, "tiles");
        base.mkdirs();
        tiles.mkdirs();
        IConfigurationProvider config = Configuration.getInstance();
        config.setUserAgentValue("RandoSavoie/5.0 (+https://github.com/Golradreth/hash-report-tool)");
        config.setOsmdroidBasePath(base);
        config.setOsmdroidTileCache(tiles);
        config.setTileFileSystemCacheMaxBytes(350L * 1024 * 1024);
        config.setTileFileSystemCacheTrimBytes(280L * 1024 * 1024);
    }

    private void buildUi() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.rgb(238, 241, 237));

        map = new MapView(this);
        map.setTileSource(PLAN_IGN);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.setTilesScaledToDpi(true);
        map.setUseDataConnection(true);
        map.getController().setZoom(15.0);
        map.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) follow = false;
            return false;
        });
        root.addView(map, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScaleBarOverlay scale = new ScaleBarOverlay(map);
        scale.setCentred(false);
        scale.setScaleBarOffset(dp(14), dp(132));
        map.getOverlays().add(scale);

        directionOverlay = new DirectionOverlay();
        map.getOverlays().add(directionOverlay);

        LinearLayout routeCard = new LinearLayout(this);
        routeCard.setOrientation(LinearLayout.VERTICAL);
        routeCard.setPadding(dp(15), dp(11), dp(15), dp(10));
        routeCard.setBackground(cardBackground(CARD, 18, Color.argb(45, 20, 42, 31)));
        routeCard.setElevation(dp(7));
        routeCard.setClickable(true);
        routeCard.setOnClickListener(view -> chooseRoute());

        LinearLayout routeHeader = new LinearLayout(this);
        routeHeader.setOrientation(LinearLayout.HORIZONTAL);
        routeHeader.setGravity(Gravity.CENTER_VERTICAL);
        routeTitle = text("Randonnée", 18, true, TEXT);
        routeTitle.setSingleLine(true);
        routeTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        routeHeader.addView(routeTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = text("⌄", 21, true, FOREST);
        chevron.setGravity(Gravity.CENTER);
        routeHeader.addView(chevron, new LinearLayout.LayoutParams(dp(32), dp(32)));
        routeCard.addView(routeHeader);

        routeMeta = text("", 13, false, Color.rgb(82, 94, 86));
        routeMeta.setSingleLine(true);
        routeCard.addView(routeMeta);

        statusChip = text("Prêt", 12, true, FOREST);
        statusChip.setGravity(Gravity.CENTER_VERTICAL);
        statusChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusChip.setBackground(pillBackground(Color.rgb(226, 240, 231)));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.topMargin = dp(7);
        routeCard.addView(statusChip, chipParams);

        android.widget.FrameLayout.LayoutParams routeParams = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        routeParams.setMargins(dp(12), dp(10), dp(12), 0);
        root.addView(routeCard, routeParams);

        LinearLayout statsCard = new LinearLayout(this);
        statsCard.setOrientation(LinearLayout.VERTICAL);
        statsCard.setPadding(dp(8), dp(7), dp(8), dp(7));
        statsCard.setBackground(cardBackground(CARD, 18, Color.argb(35, 20, 42, 31)));
        statsCard.setElevation(dp(8));
        statsCard.setClickable(true);
        statsCard.setOnClickListener(view -> toggleDetails());

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setGravity(Gravity.CENTER);
        remainingValue = addMetric(metrics, "RESTE", "—");
        timeValue = addMetric(metrics, "TEMPS", "00:00");
        altitudeValue = addMetric(metrics, "ALT.", "—");
        offRouteValue = addMetric(metrics, "ÉCART", "—");
        statsCard.addView(metrics);

        detailsPanel = new LinearLayout(this);
        detailsPanel.setOrientation(LinearLayout.VERTICAL);
        detailsPanel.setVisibility(View.GONE);
        detailsValue = text("", 12, false, Color.rgb(70, 82, 75));
        detailsValue.setGravity(Gravity.CENTER);
        detailsValue.setPadding(dp(5), dp(7), dp(5), 0);
        detailsPanel.addView(detailsValue);
        statsCard.addView(detailsPanel);

        android.widget.FrameLayout.LayoutParams statsParams = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statsParams.setMargins(dp(12), 0, dp(12), dp(82));
        root.addView(statsCard, statsParams);

        attribution = text("Plan IGN • © IGN", 10, false, Color.rgb(55, 67, 60));
        attribution.setPadding(dp(7), dp(3), dp(7), dp(3));
        attribution.setBackground(pillBackground(Color.argb(215, 255, 255, 255)));
        android.widget.FrameLayout.LayoutParams attributionParams = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        attributionParams.setMargins(dp(12), 0, 0, dp(154));
        root.addView(attribution, attributionParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(7), dp(6), dp(7), dp(6));
        actions.setBackground(cardBackground(Color.argb(248, 255, 255, 255), 28,
                Color.argb(35, 20, 42, 31)));
        actions.setElevation(dp(10));

        trackingButton = actionPill("▶  Démarrer", FOREST, Color.WHITE);
        TextView centerButton = actionCircle("⌖");
        TextView menuButton = actionCircle("⋮");
        actions.addView(trackingButton, new LinearLayout.LayoutParams(dp(150), dp(48)));
        LinearLayout.LayoutParams roundButton = new LinearLayout.LayoutParams(dp(48), dp(48));
        roundButton.leftMargin = dp(8);
        actions.addView(centerButton, roundButton);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        menuParams.leftMargin = dp(8);
        actions.addView(menuButton, menuParams);

        android.widget.FrameLayout.LayoutParams actionParams = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        actionParams.bottomMargin = dp(14);
        root.addView(actions, actionParams);

        trackingButton.setOnClickListener(view -> toggleTracking());
        centerButton.setOnClickListener(view -> center());
        menuButton.setOnClickListener(this::showActionsMenu);
        setContentView(root);
    }

    private TextView addMetric(LinearLayout parent, String label, String initialValue) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        TextView value = text(initialValue, 15, true, TEXT);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        TextView caption = text(label, 9, true, Color.rgb(104, 114, 108));
        caption.setGravity(Gravity.CENTER);
        column.addView(value);
        column.addView(caption);
        parent.addView(column, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return value;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView actionPill(String label, int background, int foreground) {
        TextView view = text(label, 14, true, foreground);
        view.setGravity(Gravity.CENTER);
        view.setBackground(pillBackground(background));
        view.setClickable(true);
        view.setElevation(dp(2));
        return view;
    }

    private TextView actionCircle(String label) {
        TextView view = text(label, 25, true, FOREST);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(238, 244, 240));
        background.setStroke(dp(1), Color.rgb(212, 225, 217));
        view.setBackground(background);
        view.setClickable(true);
        return view;
    }

    private GradientDrawable cardBackground(int color, int radiusDp, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private GradientDrawable pillBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(999));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void chooseRoute() {
        if (routes.isEmpty()) {
            Toast.makeText(this, "Ajoute d'abord un fichier GPX.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[routes.size()];
        int checked = 0;
        for (int i = 0; i < routes.size(); i++) {
            labels[i] = routes.get(i).name;
            if (activeRoute != null && activeRoute.id.equals(routes.get(i).id)) checked = i;
        }
        final AlertDialog[] dialog = new AlertDialog[1];
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle("Choisir une randonnée")
                .setSingleChoiceItems(labels, checked, (whichDialog, index) -> {
                    selectRoute(routes.get(index));
                    dialog[0].dismiss();
                })
                .setNegativeButton("Fermer", null)
                .create();
        dialog[0].show();
    }

    private void showActionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 1, "Afficher toute la randonnée");
        menu.add(0, 2, 2, "Ajouter un GPX");
        menu.add(0, 3, 3, "Supprimer cette randonnée");
        menu.add(0, 4, 4, ign ? "Utiliser OpenTopoMap" : "Utiliser Plan IGN");
        menu.add(0, 5, 5, offline ? "Repasser en ligne" : "Passer hors ligne");
        menu.add(0, 6, 6, "Mémoriser le parking");
        menu.add(0, 7, 7, "Partager ma position");
        menu.add(0, 8, 8, "Réglages GPS");
        menu.add(0, 9, 9, "Réglages batterie de l’app");
        if (sessionExists) menu.add(0, 10, 10, "Terminer la randonnée");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: fitRoute(); return true;
                case 2: pickGpx(); return true;
                case 3: deleteRoute(); return true;
                case 4: toggleSource(); return true;
                case 5: toggleNetwork(); return true;
                case 6: markParking(); return true;
                case 7: sharePosition(); return true;
                case 8: openGpsSettings(); return true;
                case 9: openBatterySettings(); return true;
                case 10: confirmFinishSession(); return true;
                default: return false;
            }
        });
        popup.show();
    }

    private void toggleDetails() {
        detailsExpanded = !detailsExpanded;
        detailsPanel.setVisibility(detailsExpanded ? View.VISIBLE : View.GONE);
    }

    private void pickGpx() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_GPX);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GPX || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            Route route = repository.importGpx(data.getData());
            reloadRoutes(route.id);
            setStatus("Randonnée ajoutée", false);
        } catch (Exception error) {
            Toast.makeText(this, "GPX invalide : " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void reloadRoutes(String selectedId) {
        routes.clear();
        routes.addAll(repository.listRoutes());
        if (routes.isEmpty()) {
            activeRoute = null;
            clearRouteOverlays();
            routeTitle.setText("Aucune randonnée");
            routeMeta.setText("Ajoute un fichier GPX depuis le menu");
            refreshStats();
            return;
        }
        int index = 0;
        for (int i = 0; selectedId != null && i < routes.size(); i++) {
            if (selectedId.equals(routes.get(i).id)) index = i;
        }
        selectRoute(routes.get(index));
    }

    private void selectRoute(Route route) {
        SharedPreferences prefs = HikeService.sessionPrefs(this);
        String sessionRoute = prefs.getString(HikeService.SESSION_ROUTE_ID, null);
        if (sessionExists && sessionRoute != null && !sessionRoute.equals(route.id)) {
            Toast.makeText(this, "Termine d'abord la session en cours.", Toast.LENGTH_LONG).show();
            return;
        }
        activeRoute = route;
        if (!sessionExists) routeIndex = 0;
        repository.setActiveRouteId(route.id);
        routeTitle.setText(route.name + "  ⌄");
        routeMeta.setText(RouteMath.formatDistance(route.lengthMeters)
                + "  •  D+ " + Math.round(route.ascentMeters) + " m");
        drawRoute();
        setStatus(tracking ? "Suivi GPS actif" : sessionExists ? "Session en pause" : "Randonnée prête", false);
        refreshStats();
    }

    private void drawRoute() {
        clearRouteOverlays();
        if (activeRoute == null || activeRoute.points.size() < 2) return;
        ArrayList<org.osmdroid.util.GeoPoint> points = new ArrayList<>();
        for (GeoPoint point : activeRoute.points) {
            points.add(new org.osmdroid.util.GeoPoint(point.lat, point.lon));
        }
        routeLine = new Polyline();
        routeLine.setPoints(points);
        routeLine.setColor(ACCENT);
        routeLine.setWidth(dp(4));
        map.getOverlays().add(routeLine);
        drawWalkedLine();
        startMarker = marker(points.get(0), "Départ", Color.rgb(50, 150, 85));
        finishMarker = marker(points.get(points.size() - 1), "Arrivée", Color.rgb(195, 55, 45));
        map.getOverlays().add(startMarker);
        map.getOverlays().add(finishMarker);
        updateParkingMarker();
        bringDirectionToFront();
        map.invalidate();
        if (lastLocation != null) updateDirectionOverlay();
        else fitRoute();
    }

    private void drawWalkedLine() {
        if (walkedLine != null) map.getOverlays().remove(walkedLine);
        walkedLine = null;
        if (walkedPoints.size() < 2) return;
        ArrayList<org.osmdroid.util.GeoPoint> points = new ArrayList<>();
        for (GeoPoint point : walkedPoints) points.add(new org.osmdroid.util.GeoPoint(point.lat, point.lon));
        walkedLine = new Polyline();
        walkedLine.setPoints(points);
        walkedLine.setColor(WALKED);
        walkedLine.setWidth(dp(4));
        map.getOverlays().add(walkedLine);
    }

    private void appendWalkedPoint(Location location) {
        if (!walkedPoints.isEmpty()) {
            GeoPoint previous = walkedPoints.get(walkedPoints.size() - 1);
            Location old = new Location("old");
            old.setLatitude(previous.lat);
            old.setLongitude(previous.lon);
            if (old.distanceTo(location) < 1.5f) return;
        }
        walkedPoints.add(new GeoPoint(location.getLatitude(), location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : Double.NaN));
        drawWalkedLine();
        bringDirectionToFront();
    }

    private Marker marker(org.osmdroid.util.GeoPoint point, String title, int color) {
        Marker marker = new Marker(map);
        marker.setPosition(point);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        marker.setTitle(title);
        GradientDrawable icon = new GradientDrawable();
        icon.setShape(GradientDrawable.OVAL);
        icon.setColor(color);
        icon.setStroke(dp(3), Color.WHITE);
        icon.setSize(dp(19), dp(19));
        marker.setIcon(icon);
        return marker;
    }

    private void clearRouteOverlays() {
        if (map == null) return;
        if (routeLine != null) map.getOverlays().remove(routeLine);
        if (walkedLine != null) map.getOverlays().remove(walkedLine);
        if (startMarker != null) map.getOverlays().remove(startMarker);
        if (finishMarker != null) map.getOverlays().remove(finishMarker);
        if (parkingMarker != null) map.getOverlays().remove(parkingMarker);
        routeLine = walkedLine = null;
        startMarker = finishMarker = parkingMarker = null;
        map.invalidate();
    }

    private void bringDirectionToFront() {
        if (directionOverlay == null) return;
        map.getOverlays().remove(directionOverlay);
        map.getOverlays().add(directionOverlay);
        updateDirectionOverlay();
    }

    private void updateDirectionOverlay() {
        if (directionOverlay == null || lastLocation == null) return;
        float heading = compassHeading;
        if (lastLocation.hasBearing() && lastLocation.getSpeed() > 0.8f) {
            heading = lastLocation.getBearing();
        } else if (Float.isNaN(heading) && lastLocation.hasBearing()) {
            heading = lastLocation.getBearing();
        } else if (Float.isNaN(heading)) {
            heading = 0f;
        }
        directionOverlay.setLocation(lastLocation, heading);
        org.osmdroid.util.GeoPoint point = new org.osmdroid.util.GeoPoint(
                lastLocation.getLatitude(), lastLocation.getLongitude());
        if (!firstFixCentered) {
            firstFixCentered = true;
            if (map.getZoomLevelDouble() < 16) map.getController().setZoom(16.0);
            map.getController().animateTo(point);
        } else if (follow && tracking) {
            map.getController().animateTo(point);
        }
        map.invalidate();
    }

    private void updateParkingMarker() {
        if (parkingMarker != null) map.getOverlays().remove(parkingMarker);
        parkingMarker = null;
        if (parking == null) return;
        parkingMarker = marker(new org.osmdroid.util.GeoPoint(parking.lat, parking.lon),
                "Parking", Color.rgb(230, 145, 25));
        map.getOverlays().add(parkingMarker);
    }

    private void fitRoute() {
        follow = false;
        if (activeRoute == null || activeRoute.points.size() < 2) return;
        ArrayList<org.osmdroid.util.GeoPoint> points = new ArrayList<>();
        for (GeoPoint point : activeRoute.points) points.add(new org.osmdroid.util.GeoPoint(point.lat, point.lon));
        BoundingBox box = BoundingBox.fromGeoPoints(points);
        map.post(() -> map.zoomToBoundingBox(box, true, dp(82)));
        setStatus("Randonnée entière affichée", false);
    }

    private void center() {
        follow = true;
        if (lastLocation != null) {
            map.getController().animateTo(new org.osmdroid.util.GeoPoint(
                    lastLocation.getLatitude(), lastLocation.getLongitude()));
            if (map.getZoomLevelDouble() < 16) map.getController().setZoom(16.0);
            setStatus("Suivi centré", false);
        } else if (activeRoute != null) {
            fitRoute();
        } else {
            setStatus("Position indisponible", true);
        }
    }

    private void toggleSource() {
        ign = !ign;
        map.setTileSource(ign ? PLAN_IGN : OPEN_TOPO);
        attribution.setText(ign ? "Plan IGN • © IGN"
                : "OpenTopoMap • © OpenStreetMap contributeurs");
        setStatus(ign ? "Fond Plan IGN" : "Fond OpenTopoMap", false);
        map.invalidate();
    }

    private void toggleNetwork() {
        offline = !offline;
        map.setUseDataConnection(!offline);
        setStatus(offline ? "Mode hors ligne — cache local" : "Mode en ligne — cache actif", false);
    }

    private void deleteRoute() {
        if (activeRoute == null) return;
        if (sessionExists) {
            Toast.makeText(this, "Termine la session avant de supprimer la randonnée.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Supprimer la randonnée ?")
                .setMessage(activeRoute.name)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (dialog, which) -> {
                    repository.delete(activeRoute.id);
                    reloadRoutes(null);
                }).show();
    }

    private void toggleTracking() {
        if (tracking) {
            pauseTracking();
            return;
        }
        if (activeRoute == null) {
            Toast.makeText(this, "Sélectionne une randonnée.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), ASK_PERMISSIONS);
        else startOrResumeTracking();
    }

    private void startOrResumeTracking() {
        boolean newSession = !sessionExists;
        if (newSession) {
            walked = 0;
            elapsed = 0;
            routeIndex = 0;
            walkedPoints.clear();
            sessionExists = true;
        }
        tracking = true;
        follow = true;
        updateTrackingButton();
        setStatus(newSession ? "Démarrage du suivi GPS…" : "Reprise du suivi GPS…", false);
        Intent service = new Intent(this, HikeService.class);
        service.setAction(newSession ? HikeService.ACTION_NEW : HikeService.ACTION_RESUME);
        service.putExtra(HikeService.EXTRA_ROUTE_ID, activeRoute.id);
        startForegroundService(service);
    }

    private void pauseTracking() {
        tracking = false;
        Intent service = new Intent(this, HikeService.class);
        service.setAction(HikeService.ACTION_PAUSE);
        startService(service);
        updateTrackingButton();
        setStatus("Session en pause — progression conservée", false);
        refreshStats();
    }

    private void confirmFinishSession() {
        new AlertDialog.Builder(this)
                .setTitle("Terminer la randonnée ?")
                .setMessage("La progression et la trace parcourue seront effacées.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Terminer", (dialog, which) -> finishSession())
                .show();
    }

    private void finishSession() {
        Intent service = new Intent(this, HikeService.class);
        service.setAction(HikeService.ACTION_FINISH);
        try { startService(service); } catch (Exception ignored) { stopService(service); }
        HikeService.clearSession(this);
        sessionExists = false;
        tracking = false;
        walked = 0;
        elapsed = 0;
        routeIndex = 0;
        lastLocation = null;
        walkedPoints.clear();
        drawRoute();
        updateTrackingButton();
        setStatus("Randonnée terminée", false);
        refreshStats();
    }

    private void updateTrackingButton() {
        if (trackingButton == null) return;
        if (tracking) {
            trackingButton.setText("Ⅱ  Pause");
            trackingButton.setBackground(pillBackground(Color.rgb(164, 92, 35)));
        } else if (sessionExists) {
            trackingButton.setText("▶  Reprendre");
            trackingButton.setBackground(pillBackground(FOREST));
        } else {
            trackingButton.setText("▶  Démarrer");
            trackingButton.setBackground(pillBackground(FOREST));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                       int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != ASK_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) startOrResumeTracking();
        else setStatus("Autorisation GPS refusée", true);
    }

    private void loadSessionState() {
        SharedPreferences prefs = HikeService.sessionPrefs(this);
        sessionExists = prefs.getBoolean(HikeService.SESSION_EXISTS, false);
        tracking = prefs.getBoolean(HikeService.SESSION_TRACKING, false);
        walked = Double.longBitsToDouble(prefs.getLong(HikeService.SESSION_WALKED,
                Double.doubleToRawLongBits(0d)));
        elapsed = prefs.getLong(HikeService.SESSION_ELAPSED, 0L);
        routeIndex = prefs.getInt(HikeService.SESSION_ROUTE_INDEX, 0);
        if (prefs.getBoolean(HikeService.SESSION_HAS_LOCATION, false)) {
            Location restored = new Location("saved");
            restored.setLatitude(Double.longBitsToDouble(prefs.getLong(HikeService.SESSION_LAT, 0L)));
            restored.setLongitude(Double.longBitsToDouble(prefs.getLong(HikeService.SESSION_LON, 0L)));
            restored.setAccuracy(prefs.getFloat(HikeService.SESSION_ACCURACY, 30f));
            restored.setTime(prefs.getLong(HikeService.SESSION_LOCATION_TIME, System.currentTimeMillis()));
            if (prefs.getBoolean(HikeService.SESSION_HAS_ELE, false)) {
                restored.setAltitude(Double.longBitsToDouble(prefs.getLong(HikeService.SESSION_ELE, 0L)));
            }
            if (prefs.getBoolean(HikeService.SESSION_HAS_BEARING, false)) {
                restored.setBearing(prefs.getFloat(HikeService.SESSION_BEARING, 0f));
            }
            restored.setSpeed(prefs.getFloat(HikeService.SESSION_SPEED, 0f));
            lastLocation = restored;
        }
    }

    private void refreshStats() {
        if (activeRoute == null) {
            remainingValue.setText("—");
            timeValue.setText("00:00");
            altitudeValue.setText("—");
            offRouteValue.setText("—");
            detailsValue.setText("Aucune randonnée sélectionnée");
            return;
        }
        String remaining = "—";
        String off = "—";
        String altitude = "—";
        String accuracy = "—";
        String parkingDistance = "—";
        double offMeters = Double.NaN;
        if (lastLocation != null) {
            RouteProgress progress = RouteMath.progress(lastLocation, activeRoute,
                    sessionExists ? Math.max(0, routeIndex - 30) : 0);
            if (sessionExists) routeIndex = Math.max(routeIndex, progress.index);
            remaining = compactDistance(progress.remainingMeters);
            off = compactDistance(progress.offRouteMeters);
            offMeters = progress.offRouteMeters;
            altitude = lastLocation.hasAltitude() ? Math.round(lastLocation.getAltitude()) + " m" : "—";
            accuracy = "±" + Math.round(lastLocation.getAccuracy()) + " m";
            if (parking != null) parkingDistance = RouteMath.formatDistance(RouteMath.distance(lastLocation, parking));
            if (progress.offRouteMeters > OFF_ROUTE_METERS) {
                setStatus("Hors tracé d'environ " + Math.round(progress.offRouteMeters) + " m", true);
            } else if (tracking) {
                setStatus("Sur le tracé — GPS actif", false);
            }
        }
        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = batteryManager == null ? -1
                : batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        remainingValue.setText(remaining);
        timeValue.setText(formatElapsed(elapsed));
        altitudeValue.setText(altitude);
        offRouteValue.setText(off);
        offRouteValue.setTextColor(!Double.isNaN(offMeters) && offMeters > OFF_ROUTE_METERS
                ? WARNING : TEXT);
        String state = tracking ? "actif" : sessionExists ? "en pause" : "non démarré";
        detailsValue.setText("Session " + state + "  •  Parcouru " + RouteMath.formatDistance(walked)
                + "  •  GPS " + accuracy
                + "  •  Batterie " + (battery < 0 ? "—" : battery + " %")
                + "\nParking " + parkingDistance + "  •  Trace bleue = chemin parcouru");
    }

    private String compactDistance(double meters) {
        if (Double.isNaN(meters) || Double.isInfinite(meters)) return "—";
        if (meters >= 1000) return String.format(Locale.FRANCE, "%.1f km", meters / 1000.0);
        return Math.round(meters) + " m";
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
        bringDirectionToFront();
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
        try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
        catch (Exception error) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        }
    }

    private void setStatus(String value, boolean warning) {
        if (statusChip == null) return;
        statusChip.setText(value);
        statusChip.setTextColor(warning ? WARNING : FOREST_SOFT);
        statusChip.setBackground(pillBackground(warning
                ? Color.rgb(252, 230, 228) : Color.rgb(226, 240, 231)));
    }

    private void saveParking() {
        getSharedPreferences("parking", MODE_PRIVATE).edit()
                .putLong("lat", Double.doubleToRawLongBits(parking.lat))
                .putLong("lon", Double.doubleToRawLongBits(parking.lon))
                .putBoolean("saved", true).apply();
    }

    private GeoPoint loadParking() {
        SharedPreferences preferences = getSharedPreferences("parking", MODE_PRIVATE);
        if (!preferences.getBoolean("saved", false)) return null;
        return new GeoPoint(Double.longBitsToDouble(preferences.getLong("lat", 0L)),
                Double.longBitsToDouble(preferences.getLong("lon", 0L)), Double.NaN);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] rotation = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        SensorManager.getOrientation(rotation, orientation);
        float magnetic = (float) Math.toDegrees(orientation[0]);
        if (magnetic < 0) magnetic += 360f;
        float trueHeading = magnetic;
        if (lastLocation != null) {
            GeomagneticField field = new GeomagneticField((float) lastLocation.getLatitude(),
                    (float) lastLocation.getLongitude(),
                    (float) (lastLocation.hasAltitude() ? lastLocation.getAltitude() : 0),
                    System.currentTimeMillis());
            trueHeading = normalize(magnetic + field.getDeclination());
        }
        if (Float.isNaN(compassHeading)) compassHeading = trueHeading;
        else {
            float difference = ((trueHeading - compassHeading + 540f) % 360f) - 180f;
            compassHeading = normalize(compassHeading + difference * 0.18f);
        }
        updateDirectionOverlay();
    }

    private float normalize(float angle) {
        float normalized = angle % 360f;
        return normalized < 0 ? normalized + 360f : normalized;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        loadSessionState();
        walkedPoints.clear();
        walkedPoints.addAll(HikeService.loadTrack(this));
        if (map != null) {
            drawWalkedLine();
            bringDirectionToFront();
        }
        updateTrackingButton();
        refreshStats();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(HikeService.ACTION_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(locationReceiver, filter);
            receiverRegistered = true;
        }
        uiHandler.removeCallbacks(uiTicker);
        uiHandler.postDelayed(uiTicker, 1000L);
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(uiTicker);
        if (sensorManager != null) sensorManager.unregisterListener(this);
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

    private final class DirectionOverlay extends Overlay {
        private final Paint accuracyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accuracyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Location location;
        private float heading;

        DirectionOverlay() {
            accuracyFill.setColor(Color.argb(28, 39, 114, 220));
            accuracyFill.setStyle(Paint.Style.FILL);
            accuracyStroke.setColor(Color.argb(110, 39, 114, 220));
            accuracyStroke.setStyle(Paint.Style.STROKE);
            accuracyStroke.setStrokeWidth(dp(1));
            arrowShadow.setColor(Color.argb(75, 0, 0, 0));
            arrowShadow.setStyle(Paint.Style.FILL);
            arrowFill.setColor(WALKED);
            arrowFill.setStyle(Paint.Style.FILL);
            arrowStroke.setColor(Color.WHITE);
            arrowStroke.setStyle(Paint.Style.STROKE);
            arrowStroke.setStrokeWidth(dp(2));
            arrowStroke.setStrokeJoin(Paint.Join.ROUND);
        }

        void setLocation(Location value, float direction) {
            location = value;
            heading = direction;
        }

        @Override public void draw(Canvas canvas, MapView mapView, boolean shadow) {
            if (shadow || location == null) return;
            Projection projection = mapView.getProjection();
            org.osmdroid.util.GeoPoint geoPoint = new org.osmdroid.util.GeoPoint(
                    location.getLatitude(), location.getLongitude());
            Point center = projection.toPixels(geoPoint, null);
            float accuracyPixels = projection.metersToEquatorPixels(location.getAccuracy());
            double cosine = Math.cos(Math.toRadians(location.getLatitude()));
            if (cosine > 0.2) accuracyPixels /= (float) cosine;
            accuracyPixels = Math.min(accuracyPixels, dp(150));
            canvas.drawCircle(center.x, center.y, accuracyPixels, accuracyFill);
            canvas.drawCircle(center.x, center.y, accuracyPixels, accuracyStroke);

            Path arrow = new Path();
            arrow.moveTo(0, -dp(19));
            arrow.lineTo(dp(12), dp(15));
            arrow.lineTo(0, dp(9));
            arrow.lineTo(-dp(12), dp(15));
            arrow.close();

            canvas.save();
            canvas.translate(center.x + dp(1), center.y + dp(2));
            canvas.rotate(heading - mapView.getMapOrientation());
            canvas.drawPath(arrow, arrowShadow);
            canvas.restore();

            canvas.save();
            canvas.translate(center.x, center.y);
            canvas.rotate(heading - mapView.getMapOrientation());
            canvas.drawPath(arrow, arrowFill);
            canvas.drawPath(arrow, arrowStroke);
            canvas.drawCircle(0, dp(4), dp(3), arrowStroke);
            canvas.restore();
        }
    }
}
