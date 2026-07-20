package com.golradreth.randooffline;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Xml;

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
    final double[] cumulativeMeters;
    final double lengthMeters;
    final double ascentMeters;

    Route(String id, String name, List<GeoPoint> points) {
        this.id = id;
        this.name = name;
        this.points = new ArrayList<>(points);
        this.cumulativeMeters = RouteMath.cumulative(this.points);
        this.lengthMeters = cumulativeMeters.length == 0
                ? 0
                : cumulativeMeters[cumulativeMeters.length - 1];
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
    private static final double EARTH_RADIUS = 6_371_000.0;

    private RouteMath() {
    }

    static RouteProgress progress(Location location, Route route, int startIndex) {
        if (route == null || route.points.size() < 2) {
            return new RouteProgress(0, Double.NaN, Double.NaN);
        }

        int start = Math.max(0, Math.min(startIndex, route.points.size() - 2));
        int bestIndex = start;
        double bestDistance = Double.MAX_VALUE;
        double bestAlong = route.cumulativeMeters[start];

        double latitudeReference = Math.toRadians(location.getLatitude());
        double px = Math.toRadians(location.getLongitude()) * Math.cos(latitudeReference) * EARTH_RADIUS;
        double py = Math.toRadians(location.getLatitude()) * EARTH_RADIUS;

        for (int i = start; i < route.points.size() - 1; i++) {
            GeoPoint first = route.points.get(i);
            GeoPoint second = route.points.get(i + 1);

            double ax = Math.toRadians(first.lon) * Math.cos(latitudeReference) * EARTH_RADIUS;
            double ay = Math.toRadians(first.lat) * EARTH_RADIUS;
            double bx = Math.toRadians(second.lon) * Math.cos(latitudeReference) * EARTH_RADIUS;
            double by = Math.toRadians(second.lat) * EARTH_RADIUS;

            double abx = bx - ax;
            double aby = by - ay;
            double abSquared = abx * abx + aby * aby;
            double t = abSquared <= 0
                    ? 0
                    : ((px - ax) * abx + (py - ay) * aby) / abSquared;
            t = Math.max(0, Math.min(1, t));

            double nearestX = ax + t * abx;
            double nearestY = ay + t * aby;
            double dx = px - nearestX;
            double dy = py - nearestY;
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
                double segmentLength = route.cumulativeMeters[i + 1] - route.cumulativeMeters[i];
                bestAlong = route.cumulativeMeters[i] + t * segmentLength;
            }
        }

        return new RouteProgress(
                bestIndex,
                bestDistance,
                Math.max(0, route.lengthMeters - bestAlong)
        );
    }

    static double nearestDistance(Location location, Route route, int startIndex) {
        return progress(location, route, startIndex).offRouteMeters;
    }

    static double[] cumulative(List<GeoPoint> points) {
        double[] result = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            result[i] = result[i - 1] + distance(points.get(i - 1), points.get(i));
        }
        return result;
    }

    static double ascent(List<GeoPoint> points) {
        double result = 0;
        double previousAccepted = Double.NaN;

        for (GeoPoint point : points) {
            if (Double.isNaN(point.ele)) continue;
            if (Double.isNaN(previousAccepted)) {
                previousAccepted = point.ele;
                continue;
            }

            double gain = point.ele - previousAccepted;
            if (Math.abs(gain) >= 1.5 && Math.abs(gain) < 80) {
                if (gain > 0) result += gain;
                previousAccepted = point.ele;
            }
        }
        return result;
    }

    static double distance(Location location, GeoPoint point) {
        float[] output = new float[1];
        Location.distanceBetween(
                location.getLatitude(),
                location.getLongitude(),
                point.lat,
                point.lon,
                output
        );
        return output[0];
    }

    static double distance(GeoPoint first, GeoPoint second) {
        float[] output = new float[1];
        Location.distanceBetween(first.lat, first.lon, second.lat, second.lon, output);
        return output[0];
    }

    static String formatDistance(double meters) {
        if (Double.isNaN(meters) || Double.isInfinite(meters)) return "—";
        if (meters >= 1000) {
            return String.format(Locale.FRANCE, "%.2f km", meters / 1000.0);
        }
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
            save(new Route(
                    BUNDLED_ID,
                    "Croix du Nivolet depuis Le Sire",
                    parsed.points
            ));
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
            Route route = loadRoute(id);
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
        Route active = id == null ? null : loadRoute(id);
        if (active != null) return active;
        List<Route> all = listRoutes();
        return all.isEmpty() ? null : all.get(0);
    }

    Route loadRoute(String id) {
        if (id == null) return null;
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
        return new Route(
                id,
                prefs.getString("name_" + id, "Randonnée"),
                points
        );
    }

    String getActiveRouteId() {
        return prefs.getString(ACTIVE, null);
    }

    void setActiveRouteId(String id) {
        prefs.edit().putString(ACTIVE, id).apply();
    }

    void delete(String id) {
        if (id == null) return;
        routeFile(id).delete();

        Set<String> ids = new HashSet<>(prefs.getStringSet(IDS, Collections.emptySet()));
        ids.remove(id);

        SharedPreferences.Editor editor = prefs.edit()
                .putStringSet(IDS, ids)
                .remove("name_" + id);
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
                    if (candidate != null && !candidate.trim().isEmpty()) {
                        routeName = candidate.trim();
                    }
                }
            } else if (event == XmlPullParser.END_TAG
                    && insidePoint
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
