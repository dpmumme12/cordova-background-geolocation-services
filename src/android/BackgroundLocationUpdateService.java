package com.flybuy.cordova.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Criteria;
import android.location.Location;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.view.ContentInfoCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.Random;

//Detected Activities imports

public class BackgroundLocationUpdateService extends Service
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = "-LocationUpdateService";
    private static final Integer SECONDS_PER_MINUTE = 60;
    private static final Integer MILLISECONDS_PER_SECOND = 60;
    private DetectedActivity lastActivity;
    private Boolean fastestSpeed = false;
    private PendingIntent locationUpdatePI;
    private FusedLocationProviderClient mFusedLocationClient;
    private PendingIntent detectedActivitiesPI;
    private GoogleApiClient detectedActivitiesAPI;
    private Integer desiredAccuracy = 100;
    private Integer distanceFilter = 30;
    private Integer activitiesInterval = 1000;
    private long interval = (long) SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND * 5;
    private long fastestInterval = (long) SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND;
    private long aggressiveInterval = (long) MILLISECONDS_PER_SECOND * 4;

    private Boolean isDebugging;
    private String notificationTitle = "Background checking";
    private String notificationText = "ENABLED";
    private Boolean useActivityDetection = false;

    private Boolean isRequestingActivity = false;
    private Boolean isRecording = false;

    private ToneGenerator toneGenerator;

    private Criteria criteria;

    private ConnectivityManager connectivityManager;
    private NotificationManager notificationManager;

    private LocationRequest locationRequest;
    //Receivers for setting the plugin to a certain state
    private final BroadcastReceiver startAggressiveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setStartAggressiveTrackingOn();
        }
    };
    /**
     * Broadcast receiver for receiving a single-update from LocationManager.
     */
    private final BroadcastReceiver locationUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LocationResult.hasResult(intent)) {
                LocationResult result = LocationResult.extractResult(intent);

                Location location;
                if (result != null) {
                    location = result.getLastLocation();

                    if (location != null) {
                        Intent mIntent = new Intent(Constants.CALLBACK_LOCATION_UPDATE);
                        mIntent.putExtras(createLocationBundle(location));
                        getApplicationContext().sendBroadcast(mIntent);
                    }
                }
            }

            if (LocationAvailability.hasLocationAvailability(intent)) {
                LocationAvailability locationAvailability = LocationAvailability.extractLocationAvailability(intent);
                if (locationAvailability != null && !locationAvailability.isLocationAvailable()) {
                    Intent mIntent = new Intent(Constants.CALLBACK_LOCATION_UPDATE);
                    mIntent.putExtra("error", "Location Provider is not available. Maybe GPS is disabled or the provider was rejected?");
                    getApplicationContext().sendBroadcast(mIntent);
                }
            }
        }
    };
    private boolean startRecordingOnConnect = true;
    private final BroadcastReceiver detectedActivitiesReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
            ArrayList<DetectedActivity> detectedActivities = (ArrayList) result.getProbableActivities();

            //Find the activity with the highest percentage
            lastActivity = Constants.getProbableActivity(detectedActivities);

            Log.w(TAG, "MOST LIKELY ACTIVITY: " + Constants.getActivityString(lastActivity.getType()) + " " + lastActivity.getConfidence());

            Intent mIntent = new Intent(Constants.CALLBACK_ACTIVITY_UPDATE);
            mIntent.putExtra(Constants.ACTIVITY_EXTRA, detectedActivities);
            getApplicationContext().sendBroadcast(mIntent);
            Log.w(TAG, "Activity is recording" + isRecording);

            if (lastActivity.getType() == DetectedActivity.STILL && isRecording) {
                showDebugToast(context, "Detected Activity was STILL, Stop recording");
                stopRecording();
            } else if (lastActivity.getType() != DetectedActivity.STILL && !isRecording) {
                showDebugToast(context, "Detected Activity was ACTIVE, Start Recording");
                startRecording();
            }
            //else do nothing
        }
    };
    private final GoogleApiClient.ConnectionCallbacks cb = new GoogleApiClient.ConnectionCallbacks() {
        @Override
        public void onConnected(Bundle bundle) {
            Log.w(TAG, "Activity Client Connected");
            ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(
                    detectedActivitiesAPI,
                    activitiesInterval,
                    detectedActivitiesPI
            );
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.w(TAG, "Connection To Activity Suspended");
            showDebugToast(getApplicationContext(), "Activity Client Suspended");
        }
    };
    private final GoogleApiClient.OnConnectionFailedListener failedCb = new GoogleApiClient.OnConnectionFailedListener() {
        @Override
        public void onConnectionFailed(@NonNull ConnectionResult cr) {
            Log.w(TAG, "ERROR CONNECTING TO DETECTED ACTIVITIES");
        }
    };
    private final BroadcastReceiver startRecordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isDebugging) {
                Log.d(TAG, "- Start Recording Receiver");
            }

            if (useActivityDetection) {
                Log.d(TAG, "STARTING ACTIVITY DETECTION");
                startDetectingActivities();
            }

            startRecording();
        }
    };
    private final BroadcastReceiver stopRecordingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isDebugging) {
                Log.d(TAG, "- Stop Recording Receiver");
            }
            if (useActivityDetection) {
                stopDetectingActivities();
            }

            stopRecording();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        // TODO Auto-generated method stub
        Log.i(TAG, "OnBind" + intent);

        return null;
    }

    @SuppressLint({"WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OnCreate");

        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
        notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // Location Update PI
        Intent locationUpdateIntent = new Intent(Constants.LOCATION_UPDATE);
        @ContentInfoCompat.Flags int intentFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT;
        locationUpdatePI = PendingIntent.getBroadcast(
                this,
                9001,
                locationUpdateIntent,
                intentFlags
        );

        Intent detectedActivitiesIntent = new Intent(Constants.DETECTED_ACTIVITY_UPDATE);
        detectedActivitiesPI = PendingIntent.getBroadcast(
                this,
                9002,
                detectedActivitiesIntent,
                intentFlags
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationUpdateReceiver, new IntentFilter(Constants.LOCATION_UPDATE), Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(detectedActivitiesReceiver, new IntentFilter(Constants.DETECTED_ACTIVITY_UPDATE), Context.RECEIVER_NOT_EXPORTED);

            // Receivers for start/stop recording
            registerReceiver(startRecordingReceiver, new IntentFilter(Constants.START_RECORDING), Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(stopRecordingReceiver, new IntentFilter(Constants.STOP_RECORDING), Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(startAggressiveReceiver, new IntentFilter(Constants.CHANGE_AGGRESSIVE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(locationUpdateReceiver, new IntentFilter(Constants.LOCATION_UPDATE));
            registerReceiver(detectedActivitiesReceiver, new IntentFilter(Constants.DETECTED_ACTIVITY_UPDATE));

            // Receivers for start/stop recording
            registerReceiver(startRecordingReceiver, new IntentFilter(Constants.START_RECORDING));
            registerReceiver(stopRecordingReceiver, new IntentFilter(Constants.STOP_RECORDING));
            registerReceiver(startAggressiveReceiver, new IntentFilter(Constants.CHANGE_AGGRESSIVE));
        }



        // Location criteria
        criteria = new Criteria();
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(true);
        criteria.setCostAllowed(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        if (intent != null) {

            distanceFilter = Integer.parseInt(intent.getStringExtra("distanceFilter"));
            desiredAccuracy = Integer.parseInt(intent.getStringExtra("desiredAccuracy"));

            interval = Integer.parseInt(intent.getStringExtra("interval"));
            fastestInterval = Integer.parseInt(intent.getStringExtra("fastestInterval"));
            aggressiveInterval = Integer.parseInt(intent.getStringExtra("aggressiveInterval"));
            activitiesInterval = Integer.parseInt(intent.getStringExtra("activitiesInterval"));

            isDebugging = Boolean.parseBoolean(intent.getStringExtra("isDebugging"));
            notificationTitle = intent.getStringExtra("notificationTitle");
            notificationText = intent.getStringExtra("notificationText");

            useActivityDetection = Boolean.parseBoolean(intent.getStringExtra("useActivityDetection"));


            // Build the notification / pending intent
            Intent main = new Intent(this, BackgroundLocationServicesPlugin.class);
            main.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Context context = getApplicationContext();

            String channelId;
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                channelId = createNotificationChannel("BackgroundLocationUpdateService", "Background Location Update Service");
            } else {
                channelId = "";
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId);

            builder.setOngoing(true);
            builder.setCategory(Notification.CATEGORY_SERVICE);

            builder.setContentTitle(notificationTitle);
            builder.setContentText(notificationText);
            builder.setSmallIcon(context.getApplicationInfo().icon);

            Bitmap bm = BitmapFactory.decodeResource(context.getResources(),
                    context.getApplicationInfo().icon);

            if (bm != null) {
                float mult = getImageFactor(getResources());
                Bitmap scaledBm = Bitmap.createScaledBitmap(bm, (int) (bm.getWidth() * mult), (int) (bm.getHeight() * mult), false);

                if (scaledBm != null) {
                    builder.setLargeIcon(scaledBm);
                }
            }


            // Integer resId = getPluginResource("location_icon");
            //
            // //Scale our location_icon.png for different phone resolutions
            // //TODO: Get this icon via a filepath from the user
            // if(resId != 0) {
            //     Bitmap bm = BitmapFactory.decodeResource(getResources(), resId);
            //
            //     float mult = getImageFactor(getResources());
            //     Bitmap scaledBm = Bitmap.createScaledBitmap(bm, (int)(bm.getWidth()*mult), (int)(bm.getHeight()*mult), false);
            //
            //     if(scaledBm != null) {
            //         builder.setLargeIcon(scaledBm);
            //     }
            // } else {
            //     Log.w(TAG, "Could NOT find Resource for large icon");
            // }

            //Make clicking the event link back to the main cordova activity
            builder.setContentIntent(pendingIntent);
            setClickEvent(builder);

            Notification notification = builder.build();
            //notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_NO_CLEAR;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                startForeground(startId, notification);
            } else {
                startForeground(
                        startId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                );
            }
        }

        // Log.i(TAG, "- url: " + url);
        // Log.i(TAG, "- params: "  + params.toString());
        Log.i(TAG, "- interval: " + interval);
        Log.i(TAG, "- fastestInterval: " + fastestInterval);

        Log.i(TAG, "- distanceFilter: " + distanceFilter);
        Log.i(TAG, "- desiredAccuracy: " + desiredAccuracy);
        Log.i(TAG, "- isDebugging: " + isDebugging);
        Log.i(TAG, "- notificationTitle: " + notificationTitle);
        Log.i(TAG, "- notificationText: " + notificationText);
        Log.i(TAG, "- useActivityDetection: " + useActivityDetection);
        Log.i(TAG, "- activityDetectionInterval: " + activitiesInterval);

        //We want this service to continue running until it is explicitly stopped
        return START_REDELIVER_INTENT;
    }

    private void showDebugToast(Context ctx, String msg) {
        if (isDebugging) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        }
    }

    //Helper function to get the screen scale for our big icon
    public float getImageFactor(Resources r) {
        DisplayMetrics metrics = r.getDisplayMetrics();
        float multiplier = metrics.density / 3f;
        return multiplier;
    }

    //retrieves the plugin resource ID from our resources folder for a given drawable name
    public Integer getPluginResource(String resourceName) {
        return getApplication().getResources().getIdentifier(resourceName, "drawable", getApplication().getPackageName());
    }

    /**
     * Adds an onclick handler to the notification
     */
    private NotificationCompat.Builder setClickEvent(NotificationCompat.Builder notification) {
        Context context = getApplicationContext();
        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int requestCode = new Random().nextInt();

        PendingIntent contentIntent = PendingIntent.getActivity(context, requestCode, launchIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);

        return notification.setContentIntent(contentIntent);
    }

    private Bundle createLocationBundle(Location location) {
        Bundle b = new Bundle();
        b.putDouble("latitude", location.getLatitude());
        b.putDouble("longitude", location.getLongitude());
        b.putDouble("accuracy", location.getAccuracy());
        b.putDouble("altitude", location.getAltitude());
        b.putDouble("timestamp", location.getTime());
        b.putDouble("speed", location.getSpeed());
        b.putDouble("heading", location.getBearing());

        return b;
    }

    private void setStartAggressiveTrackingOn() {
        if (!fastestSpeed && this.isRecording) {
            detachRecorder();

            desiredAccuracy = 10;
            fastestInterval = (long) (aggressiveInterval / 2);
            interval = aggressiveInterval;

            attachRecorder();

            Log.e(TAG, "Changed Location params" + locationRequest.toString());
            fastestSpeed = true;
        }
    }

    public void startDetectingActivities() {
        this.isRequestingActivity = true;
        attachDARecorder();
    }

    public void stopDetectingActivities() {
        this.isRequestingActivity = false;
        detatchDARecorder();
    }

    private void attachDARecorder() {
        if (detectedActivitiesAPI == null) {
            buildDAClient();
        } else if (detectedActivitiesAPI.isConnected()) {
            ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(
                    detectedActivitiesAPI,
                    this.activitiesInterval,
                    detectedActivitiesPI
            );
            if (isDebugging) {
                Log.d(TAG, "- DA RECORDER attached - start recording location updates");
            }
        } else {
            Log.i(TAG, "NOT CONNECTED, CONNECT");
            detectedActivitiesAPI.connect();
        }
    }

    private void detatchDARecorder() {
        if (detectedActivitiesAPI == null) {
            buildDAClient();
        } else if (detectedActivitiesAPI.isConnected()) {
            ActivityRecognition.ActivityRecognitionApi.removeActivityUpdates(detectedActivitiesAPI, detectedActivitiesPI);
            if (isDebugging) {
                Log.d(TAG, "- Recorder detached - stop recording activity updates");
            }
        } else {
            detectedActivitiesAPI.connect();
        }
    }

    public void startRecording() {
        Log.w(TAG, "Started Recording Locations");
        this.startRecordingOnConnect = true;
        attachRecorder();
    }

    public void stopRecording() {
        this.startRecordingOnConnect = false;
        detachRecorder();
    }

    protected synchronized void buildDAClient() {
        Log.i(TAG, "BUILDING DA CLIENT");
        detectedActivitiesAPI = new GoogleApiClient.Builder(this)
                .addApi(ActivityRecognition.API)
                .addConnectionCallbacks(cb)
                .addOnConnectionFailedListener(failedCb)
                .build();

        detectedActivitiesAPI.connect();
    }

    protected synchronized void connectToPlayAPI() {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    private void attachRecorder() {
        Log.i(TAG, "Attaching Recorder");
        if (mFusedLocationClient == null) {
            connectToPlayAPI();
            //} else {
            locationRequest = LocationRequest.create()
                    .setPriority(translateDesiredAccuracy(desiredAccuracy))
                    .setFastestInterval(fastestInterval)
                    .setInterval(interval)
                    .setSmallestDisplacement(distanceFilter);


            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                if (isDebugging) {
                    Log.d(TAG, "- Recorder not attached - permissions not available");
                    return;
                }
            }

            mFusedLocationClient.requestLocationUpdates(locationRequest, locationUpdatePI);
            this.isRecording = true;
            if (isDebugging) {
                Log.d(TAG, "- Recorder attached - start recording location updates");
            }
        }
    }

    private void detachRecorder() {
        if (mFusedLocationClient == null) {
            connectToPlayAPI();
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                if (isDebugging) {
                    Log.d(TAG, "- Recorder not dettached - permissions not available");
                    return;
                }
                return;
            }
            mFusedLocationClient.removeLocationUpdates(locationUpdatePI);
            this.isRecording = false;
            if (isDebugging) {
                Log.w(TAG, "- Recorder detached - stop recording location updates");
            }
        }
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(TAG, "- Connected to Play API -- All ready to record");
        if (this.startRecordingOnConnect) {
            attachRecorder();
        } else {
            detachRecorder();
        }
    }

    @Override
    public void onConnectionFailed(com.google.android.gms.common.ConnectionResult result) {
        Log.e(TAG, "We failed to connect to the Google API! Possibly API is not installed on target.");
    }

    @Override
    public void onConnectionSuspended(int cause) {
        // locationClientAPI.connect();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel(String channelId, String channelName) {
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE);

        channel.setLightColor(Color.BLUE);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(channel);

        return channelId;
    }

    /**
     * Translates a number representing desired accuracy of GeoLocation system from set [0, 10, 100, 1000].
     * 0:  most aggressive, most accurate, worst battery drain
     * 1000:  least aggressive, least accurate, best for battery.
     */
    private Integer translateDesiredAccuracy(Integer accuracy) {
        if (accuracy <= 0) {
            accuracy = LocationRequest.PRIORITY_HIGH_ACCURACY;
        } else if (accuracy <= 100) {
            accuracy = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
        } else if (accuracy <= 1000) {
            accuracy = LocationRequest.PRIORITY_LOW_POWER;
        } else if (accuracy <= 10000) {
            accuracy = LocationRequest.PRIORITY_NO_POWER;
        } else {
            accuracy = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
        }

        return accuracy;
    }

    @Override
    public boolean stopService(Intent intent) {
        Log.i(TAG, "- Received stop: " + intent);
        this.stopRecording();
        this.cleanUp();

        showDebugToast(this, "Background location tracking stopped");
        return super.stopService(intent);
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Destroyed Location Update Service - Cleaning up");
        this.cleanUp();
        super.onDestroy();
    }

    private void cleanUpReceivers() {
        try {
            unregisterReceiver(locationUpdateReceiver);
            unregisterReceiver(startRecordingReceiver);
            unregisterReceiver(stopRecordingReceiver);
            unregisterReceiver(detectedActivitiesReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error: Could not unregister receiver", e);
        }
    }
    private void cleanUp() {
        cleanUpReceivers();

        try {
            stopForeground(true);
        } catch (Exception e) {
            Log.e(TAG, "Error: Could not stop foreground process", e);
        }


        toneGenerator.release();
        if (mFusedLocationClient != null) {
            mFusedLocationClient.removeLocationUpdates(locationUpdatePI);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        this.stopRecording();
        this.stopSelf();
        super.onTaskRemoved(rootIntent);
    }

}
