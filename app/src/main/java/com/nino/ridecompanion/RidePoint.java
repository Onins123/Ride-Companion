package com.nino.ridecompanion;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One recorded sample during a ride: where you were, how fast, how high,
 * and how far the bike was leaning at that instant.
 *
 * We insert one of these every time a location update or a "settled" sensor
 * reading comes in (roughly every 1-2 seconds while moving).
 */
@Entity(tableName = "ride_points")
public class RidePoint {

    @PrimaryKey(autoGenerate = true)
    public long id;

    // Which ride session this point belongs to (foreign key by convention;
    // kept simple/no FK constraint so inserts never fail mid-ride).
    public long sessionId;

    public long timestamp;       // epoch millis
    public double latitude;
    public double longitude;
    public double altitudeMeters;
    public float speedKph;
    public float leanAngleDegrees; // negative = leaning left, positive = leaning right

    @NonNull
    public static RidePoint create(long sessionId, long timestamp, double lat, double lon,
                                    double altitude, float speedKph, float leanAngle) {
        RidePoint p = new RidePoint();
        p.sessionId = sessionId;
        p.timestamp = timestamp;
        p.latitude = lat;
        p.longitude = lon;
        p.altitudeMeters = altitude;
        p.speedKph = speedKph;
        p.leanAngleDegrees = leanAngle;
        return p;
    }
}
