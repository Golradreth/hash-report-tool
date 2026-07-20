package com.golradreth.nivolet;

import android.content.Context;
import android.net.Uri;
import org.xmlpull.v1.XmlPullParser;
import android.util.Xml;
import java.io.*;
import java.util.*;

public final class GpxStore {
    private static final String FILE = "route.csv";
    public static List<GeoPoint> importGpx(Context c, Uri uri) throws Exception {
        List<GeoPoint> pts = new ArrayList<>();
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            XmlPullParser p = Xml.newPullParser(); p.setInput(in, null);
            double lat=0, lon=0, ele=Double.NaN; boolean point=false;
            int e;
            while ((e=p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e==XmlPullParser.START_TAG) {
                    String n=p.getName();
                    if ("trkpt".equals(n)||"rtept".equals(n)) {
                        lat=Double.parseDouble(p.getAttributeValue(null,"lat"));
                        lon=Double.parseDouble(p.getAttributeValue(null,"lon")); ele=Double.NaN; point=true;
                    } else if (point && "ele".equals(n)) {
                        try { ele=Double.parseDouble(p.nextText()); } catch(Exception ignored) {}
                    }
                } else if (e==XmlPullParser.END_TAG && point && ("trkpt".equals(p.getName())||"rtept".equals(p.getName()))) {
                    pts.add(new GeoPoint(lat,lon,ele)); point=false;
                }
            }
        }
        if (pts.size()<2) throw new IOException("Le GPX ne contient pas de trace exploitable");
        save(c,pts); return pts;
    }
    public static void save(Context c,List<GeoPoint> pts) throws IOException {
        try(PrintWriter w=new PrintWriter(new OutputStreamWriter(c.openFileOutput(FILE,Context.MODE_PRIVATE)))) {
            for(GeoPoint p:pts) w.println(p.lat+","+p.lon+","+p.ele);
        }
    }
    public static List<GeoPoint> load(Context c) {
        List<GeoPoint> pts=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(c.openFileInput(FILE)))) {
            String s; while((s=r.readLine())!=null){String[] a=s.split(","); pts.add(new GeoPoint(Double.parseDouble(a[0]),Double.parseDouble(a[1]),Double.parseDouble(a[2])));}
        } catch(Exception ignored) {}
        return pts;
    }
}
