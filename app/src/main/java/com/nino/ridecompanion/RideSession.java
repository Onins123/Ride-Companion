package com.nino.ridecompanion;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Summary row for one ride, created when the ride starts and finalized
 * (endTime, aggregated stats) when the ride stops.
 *
 * This is the row the future AI agent will query when you ask things like
 * "how does this ride compare to my usual" — so keep the aggregates here
 * instead of recomputing from ride_points every time.
 */
@Entity(tableName = "ride_sessions")
public class RideSession {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long startTime;   // epoch millis
    public long endTime;     // 0 while ride is in progress

    public double distanceMeters;
    public float avgSpeedKph;
    public float maxSpeedKph;
    public float minMovingSpeedKph; // lowest non-zero speed, ignores stops

    public double elevationGainMeters;
    public double elevationLossMeters;

    public float maxLeanLeftDegrees;  // most negative lean recorded
    public float maxLeanRightDegrees; // most positive lean recorded
}
