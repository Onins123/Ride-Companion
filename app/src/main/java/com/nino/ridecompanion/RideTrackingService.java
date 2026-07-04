package com.nino.ridecompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that owns the whole "recording a ride" lifecycle:
 * GPS updates, lean-angle sensor, running stats, and persisting to Room.
 *
 * Runs as a foreground service (with a persistent notification) because
 * Android kills background location access aggressively once the screen
 * locks — this is the standard pattern for any ride/fitness tracker.
 */
public class RideTrackingService extends Service {

    public static final String ACTION_START = "com.nino.ridecompanion.action.START";
    public static final String ACTION_STOP = "com.nino.ridecompanion.action.STOP";

    public static final String BROADCAST_STATS_UPDATE = "com.nino.ridecompanion.STATS_UPDATE";
    public static final String EXTRA_DISTANCE_METERS = "distanceMeters";
    public static final String EXTRA_SPEED_KPH = "speedKph";
    public static final String EXTRA_MAX_SPEED_KPH = "maxSpeedKph";
    public static final String EXTRA_LEAN_DEGREES = "leanDegrees";
    public static final String EXTRA_ELAPSED_MILLIS = "elapsedMillis";

    private static final String CHANNEL_ID = "ride_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LeanAngleSensor leanAngleSensor;

    private AppDatabase db;
    private long currentSessionId = -1;
    private long rideStartTime = 0;
    private Location lastLocation = null;

    private double distanceMeters = 0;
    private float maxSpeedKph = 0;
    private float minMovingSpeedKph = Float.MAX_VALUE;
    private double lastAltitude = Double.NaN;
    private double elevationGain = 0;
    private double elevationLoss = 0;
    private float currentLeanDegrees = 0;
    private float maxLeanLeft = 0;   // most negative
    private float maxLeanRight = 0;  // most positive

    private static final double MIN_MOVING_SPEED_KPH = 3.0; // below this, treat as stopped (GPS noise)

    @Override
    public void onCreate() {
        super.onCreate();
        db = AppDatabase.getInstance(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();

        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        leanAngleSensor = new LeanAngleSensor(sensorManager, leanDegrees -> {
            currentLeanDegrees = leanDegrees;
            if (leanDegrees < maxLeanLeft) maxLeanLeft = leanDegrees;
            if (leanDegrees > maxLeanRight) maxLeanRight = leanDegrees;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopRide();
            return START_NOT_STICKY;
        }
        // Default / ACTION_START: begin a new ride.
        startForeground(NOTIFICATION_ID, buildNotification("Ride in progress", "0.0 km/h"));
        startRide();
        return START_STICKY;
    }

    private void startRide() {
        rideStartTime = System.currentTimeMillis();
        resetStats();

        RideSession session = new RideSession();
        session.startTime = rideStartTime;
        dbExecutor.execute(() -> currentSessionId = db.rideDao().insertSession(session));

        leanAngleSensor.start();
        // Give the rider 2 seconds to hold the bike upright before we zero the sensor.
        leanAngleSensor.calibrateZero();

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 2000L // update every ~2s
        ).setMinUpdateIntervalMillis(1000L).build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location location = result.getLastLocation();
                if (location != null) {
                    onNewLocation(location);
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            // Permission wasn't granted — MainActivity should have checked before starting the service.
            stopSelf();
        }
    }

    private void resetStats() {
        distanceMeters = 0;
        maxSpeedKph = 0;
        minMovingSpeedKph = Float.MAX_VALUE;
        lastAltitude = Double.NaN;
        elevationGain = 0;
        elevationLoss = 0;
        maxLeanLeft = 0;
        maxLeanRight = 0;
        lastLocation = null;
    }

    private void onNewLocation(Location location) {
        float speedKph = location.getSpeed() * 3.6f; // m/s -> km/h

        if (lastLocation != null) {
            distanceMeters += lastLocation.distanceTo(location);
        }
        lastLocation = location;

        if (speedKph > maxSpeedKph) maxSpeedKph = speedKph;
        if (speedKph >= MIN_MOVING_SPEED_KPH && speedKph < minMovingSpeedKph) {
            minMovingSpeedKph = speedKph;
        }

        if (location.hasAltitude()) {
            double altitude = location.getAltitude();
            if (!Double.isNaN(lastAltitude)) {
                double delta = altitude - lastAltitude;
                if (delta > 0) elevationGain += delta;
                else elevationLoss += -delta;
            }
            lastAltitude = altitude;
        }

        final long sessionId = currentSessionId;
        final long now = System.currentTimeMillis();
        final double altitudeToSave = location.hasAltitude() ? location.getAltitude() : 0;
        final float leanToSave = currentLeanDegrees;

        if (sessionId > 0) {
            dbExecutor.execute(() -> db.rideDao().insertPoint(
                    RidePoint.create(sessionId, now, location.getLatitude(), location.getLongitude(),
                            altitudeToSave, speedKph, leanToSave)
            ));
        }

        broadcastStats(speedKph);
        updateNotification(speedKph);
    }

    private void broadcastStats(float speedKph) {
        Intent update = new Intent(BROADCAST_STATS_UPDATE);
        update.putExtra(EXTRA_DISTANCE_METERS, distanceMeters);
        update.putExtra(EXTRA_SPEED_KPH, speedKph);
        update.putExtra(EXTRA_MAX_SPEED_KPH, maxSpeedKph);
        update.putExtra(EXTRA_LEAN_DEGREES, currentLeanDegrees);
        update.putExtra(EXTRA_ELAPSED_MILLIS, System.currentTimeMillis() - rideStartTime);
        LocalBroadcastManager.getInstance(this).sendBroadcast(update);
    }

    private void stopRide() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        leanAngleSensor.stop();

        final long sessionId = currentSessionId;
        final long endTime = System.currentTimeMillis();
        final double finalDistance = distanceMeters;
        final float finalMaxSpeed = maxSpeedKph;
        final float finalMinMovingSpeed = (minMovingSpeedKph == Float.MAX_VALUE) ? 0 : minMovingSpeedKph;
        final double finalElevationGain = elevationGain;
        final double finalElevationLoss = elevationLoss;
        final float finalMaxLeanLeft = maxLeanLeft;
        final float finalMaxLeanRight = maxLeanRight;
        final long finalStartTime = rideStartTime;

        dbExecutor.execute(() -> {
            RideSession session = db.rideDao().getSessionById(sessionId);
            if (session != null) {
                session.endTime = endTime;
                session.distanceMeters = finalDistance;
                session.maxSpeedKph = finalMaxSpeed;
                session.minMovingSpeedKph = finalMinMovingSpeed;
                double hours = (endTime - finalStartTime) / 3_600_000.0;
                session.avgSpeedKph = hours > 0 ? (float) ((finalDistance / 1000.0) / hours) : 0;
                session.elevationGainMeters = finalElevationGain;
                session.elevationLossMeters = finalElevationLoss;
                session.maxLeanLeftDegrees = finalMaxLeanLeft;
                session.maxLeanRightDegrees = finalMaxLeanRight;
                db.rideDao().updateSession(session);
            }
        });

        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Ride Tracking", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows live stats while a ride is being recorded");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent stopIntent = new Intent(this, RideTrackingService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_directions) // swap for a real app icon later
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .build();
    }

    private void updateNotification(float speedKph) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(
                    "Ride in progress", String.format("%.0f km/h  •  %.1f km", speedKph, distanceMeters / 1000.0)
            ));
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not using bound-service pattern — MainActivity listens via LocalBroadcastManager instead.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dbExecutor.shutdown();
    }
}
