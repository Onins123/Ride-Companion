package com.nino.ridecompanion;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Reads device tilt using the fused ROTATION_VECTOR sensor and converts it
 * to a "lean angle" in degrees.
 *
 * IMPORTANT — mounting orientation matters a lot here. This assumes the
 * phone is mounted in LANDSCAPE on the handlebar, screen facing the rider,
 * top of phone pointing left or right (typical handlebar clamp mount).
 * If your mount is different, you'll likely need to change which axes are
 * remapped below — test by logging rawRollDegrees while tilting the phone
 * the way it'll actually sit on the bike, and adjust AXIS_X/AXIS_Z until
 * leaning the bike right gives a positive number.
 *
 * Call calibrateZero() once while the bike is upright and stationary at the
 * start of a ride — this removes any small mounting misalignment.
 */
public class LeanAngleSensor implements SensorEventListener {

    public interface Listener {
        void onLeanAngleChanged(float leanDegrees);
    }

    private final SensorManager sensorManager;
    private final Sensor rotationVectorSensor;
    private final Listener listener;

    private final float[] rotationMatrix = new float[9];
    private final float[] remappedMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    // Simple exponential low-pass filter to smooth out jitter.
    private static final float FILTER_ALPHA = 0.15f;
    private float filteredRollDegrees = 0f;
    private float calibrationOffsetDegrees = 0f;
    private boolean hasReading = false;

    public LeanAngleSensor(SensorManager sensorManager, Listener listener) {
        this.sensorManager = sensorManager;
        this.listener = listener;
        this.rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    public boolean isAvailable() {
        return rotationVectorSensor != null;
    }

    public void start() {
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    /** Call while the bike is upright and stationary to zero out mounting offset. */
    public void calibrateZero() {
        calibrationOffsetDegrees = filteredRollDegrees + calibrationOffsetDegrees; // absorb current reading as "zero"
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);

        // Remap for landscape handlebar mount — adjust these two constants
        // if your physical mount orientation differs (see class comment).
        SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X, SensorManager.AXIS_MINUS_Z,
                remappedMatrix
        );

        SensorManager.getOrientation(remappedMatrix, orientationAngles);

        // orientationAngles[2] is roll in radians in the remapped frame.
        float rawRollDegrees = (float) Math.toDegrees(orientationAngles[2]);

        if (!hasReading) {
            filteredRollDegrees = rawRollDegrees;
            hasReading = true;
        } else {
            filteredRollDegrees = filteredRollDegrees + FILTER_ALPHA * (rawRollDegrees - filteredRollDegrees);
        }

        float leanDegrees = filteredRollDegrees - calibrationOffsetDegrees;
        listener.onLeanAngleChanged(leanDegrees);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op — rotation vector accuracy changes are rare and not critical here.
    }
}
