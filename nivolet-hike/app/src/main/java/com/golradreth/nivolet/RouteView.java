package com.golradreth.nivolet;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.view.View;
import java.util.*;

public class RouteView extends View {
    private List<GeoPoint> route=new ArrayList<>(); private Location current;
    private final Paint path=new Paint(1), user=new Paint(1), start=new Paint(1), finish=new Paint(1), text=new Paint(1);
    public RouteView(Context c){super(c); path.setColor(Color.rgb(20,110,70)); path.setStyle(Paint.Style.STROKE); path.setStrokeWidth(9); path.setStrokeCap(Paint.Cap.ROUND); path.setStrokeJoin(Paint.Join.ROUND); user.setColor(Color.rgb(30,100,220)); start.setColor(Color.rgb(45,160,80)); finish.setColor(Color.rgb(190,50,45)); text.setColor(Color.DKGRAY); text.setTextSize(34);}
    public void setRoute(List<GeoPoint> r){route=r; invalidate();}
    public void setCurrent(Location l){current=l; invalidate();}
    @Override protected void onDraw(Canvas c){super.onDraw(c); c.drawColor(Color.rgb(245,241,232)); if(route.size()<2){text.setTextAlign(Paint.Align.CENTER);c.drawText("Importe le GPX pour afficher le tracé",getWidth()/2f,getHeight()/2f,text);return;}
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180; for(GeoPoint p:route){minLat=Math.min(minLat,p.lat);maxLat=Math.max(maxLat,p.lat);minLon=Math.min(minLon,p.lon);maxLon=Math.max(maxLon,p.lon);} if(current!=null){minLat=Math.min(minLat,current.getLatitude());maxLat=Math.max(maxLat,current.getLatitude());minLon=Math.min(minLon,current.getLongitude());maxLon=Math.max(maxLon,current.getLongitude());}
        float pad=45; double dLat=Math.max(0.0002,maxLat-minLat), dLon=Math.max(0.0002,maxLon-minLon); double scale=Math.min((getWidth()-2*pad)/dLon,(getHeight()-2*pad)/dLat);
        Path pp=new Path(); boolean first=true; for(GeoPoint p:route){float x=(float)(pad+(p.lon-minLon)*scale);float y=(float)(getHeight()-pad-(p.lat-minLat)*scale);if(first){pp.moveTo(x,y);first=false;}else pp.lineTo(x,y);} c.drawPath(pp,path);
        GeoPoint a=route.get(0),b=route.get(route.size()-1); c.drawCircle((float)(pad+(a.lon-minLon)*scale),(float)(getHeight()-pad-(a.lat-minLat)*scale),14,start); c.drawCircle((float)(pad+(b.lon-minLon)*scale),(float)(getHeight()-pad-(b.lat-minLat)*scale),16,finish);
        if(current!=null){float x=(float)(pad+(current.getLongitude()-minLon)*scale),y=(float)(getHeight()-pad-(current.getLatitude()-minLat)*scale);user.setStyle(Paint.Style.FILL);c.drawCircle(x,y,17,user);user.setStyle(Paint.Style.STROKE);user.setStrokeWidth(4);c.drawCircle(x,y,28,user);}
        text.setTextAlign(Paint.Align.LEFT); text.setTextSize(28); c.drawText("N ↑",18,34,text);
    }
}
