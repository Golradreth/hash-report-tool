package com.golradreth.nivolet;

import android.Manifest;import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.location.*;import android.os.*;import androidx.annotation.Nullable;

public class HikeService extends Service implements LocationListener {
    public static final String ACTION="com.golradreth.nivolet.LOCATION"; private LocationManager lm;
    @Override public void onCreate(){super.onCreate(); createChannel(); Notification n=new Notification.Builder(this,"hike").setContentTitle("Suivi randonnée actif").setContentText("Nivolet Hors-Ligne utilise le GPS").setSmallIcon(com.golradreth.nivolet.R.drawable.ic_launcher).setOngoing(true).build(); startForeground(7,n); lm=(LocationManager)getSystemService(LOCATION_SERVICE); if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,3000,3,this);}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel("hike","Suivi randonnée",NotificationManager.IMPORTANCE_LOW));}
    @Override public void onLocationChanged(Location l){Intent i=new Intent(ACTION);i.setPackage(getPackageName());i.putExtra("location",l);sendBroadcast(i);}
    @Override public void onDestroy(){if(lm!=null)lm.removeUpdates(this);super.onDestroy();}
    @Nullable @Override public IBinder onBind(Intent i){return null;}
}
