package com.nino.ridecompanion;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 100;

    private TextView speedText, distanceText, maxSpeedText, leanText, elapsedText, statusText;
    private Button startButton, stopButton;

    private boolean isRiding = false;

    private final BroadcastReceiver statsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double distanceMeters = intent.getDoubleExtra(RideTrackingService.EXTRA_DISTANCE_METERS, 0);
            float speedKph = intent.getFloatExtra(RideTrackingService.EXTRA_SPEED_KPH, 0);
            float maxSpeedKph = intent.getFloatExtra(RideTrackingService.EXTRA_MAX_SPEED_KPH, 0);
            float leanDegrees = intent.getFloatExtra(RideTrackingService.EXTRA_LEAN_DEGREES, 0);
            long elapsedMillis = intent.getLongExtra(RideTrackingService.EXTRA_ELAPSED_MILLIS, 0);

            speedText.setText(String.format(Locale.getDefault(), "%.0f km/h", speedKph));
            distanceText.setText(String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1000.0));
            maxSpeedText.setText(String.format(Locale.getDefault(), "Max: %.0f km/h", maxSpeedKph));
            leanText.setText(String.format(Locale.getDefault(), "Lean: %.0f°", leanDegrees));
            elapsedText.setText(formatElapsed(elapsedMillis));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        speedText = findViewById(R.id.speedText);
        distanceText = findViewById(R.id.distanceText);
        maxSpeedText = findViewById(R.id.maxSpeedText);
        leanText = findViewById(R.id.leanText);
        elapsedText = findViewById(R.id.elapsedText);
        statusText = findViewById(R.id.statusText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> onStartClicked());
        stopButton.setOnClickListener(v -> onStopClicked());
        stopButton.setEnabled(false);
    }

    private void onStartClicked() {
        if (hasAllPermissions()) {
            startRide();
        } else {
            requestPermissions();
        }
    }

    private void startRide() {
        Intent serviceIntent = new Intent(this, RideTrackingService.class);
        serviceIntent.setAction(RideTrackingService.ACTION_START);
        ContextCompat.startForegroundService(this, serviceIntent);

        isRiding = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusText.setText("Recording...");
    }

    private void onStopClicked() {
        Intent serviceIntent = new Intent(this, RideTrackingService.class);
        serviceIntent.setAction(RideTrackingService.ACTION_STOP);
        startService(serviceIntent);

        isRiding = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusText.setText("Ride saved");
    }

    private String formatElapsed(long millis) {
        long totalSeconds = millis / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s);
    }

    // ---- Permissions ----

    private boolean hasAllPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // ACCESS_BACKGROUND_LOCATION must be requested separately/after the
        // foreground permission on Android 10+ — kept out of this first
        // batch intentionally; add a follow-up request once foreground
        // location is granted if you need tracking with the screen off
        // and the app fully backgrounded.
        return permissions.toArray(new String[0]);
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_CODE_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasAllPermissions()) {
                startRide();
            } else {
                Toast.makeText(this, "Location permission is required to record a ride", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                statsReceiver, new IntentFilter(RideTrackingService.BROADCAST_STATS_UPDATE)
        );
    }

    @Override
    protected void onStop() {
        super.onStop();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statsReceiver);
    }
}
