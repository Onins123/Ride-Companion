package com.nino.ridecompanion;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface RideDao {

    @Insert
    long insertSession(RideSession session);

    @Update
    void updateSession(RideSession session);

    @Insert
    void insertPoint(RidePoint point);

    // Live-updating list for a "ride history" screen later.
    @Query("SELECT * FROM ride_sessions WHERE endTime > 0 ORDER BY startTime DESC")
    LiveData<List<RideSession>> getAllSessions();

    @Query("SELECT * FROM ride_sessions WHERE id = :sessionId LIMIT 1")
    RideSession getSessionById(long sessionId);

    @Query("SELECT * FROM ride_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    List<RidePoint> getPointsForSession(long sessionId);

    // Handy for the future agent: "what's my average max speed over my last N rides"
    @Query("SELECT AVG(maxSpeedKph) FROM ride_sessions WHERE endTime > 0 ORDER BY startTime DESC LIMIT :lastN")
    Float getAverageMaxSpeed(int lastN);
}
