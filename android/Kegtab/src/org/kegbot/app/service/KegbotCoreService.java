/*
 * Copyright 2012 Mike Wakerly <opensource@hoho.com>.
 *
 * This file is part of the Kegtab package from the Kegbot project. For
 * more information on Kegtab or Kegbot, see <http://kegbot.org/>.
 *
 * Kegtab is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 2.
 *
 * Kegtab is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with Kegtab. If not, see <http://www.gnu.org/licenses/>.
 */
package org.kegbot.app.service;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.kegbot.api.KegbotApi;
import org.kegbot.api.KegbotApiException;
import org.kegbot.app.HomeActivity;
import org.kegbot.app.KegtabBroadcast;
import org.kegbot.app.R;
import org.kegbot.app.util.PreferenceHelper;
import org.kegbot.core.AuthenticationManager;
import org.kegbot.core.AuthenticationToken;
import org.kegbot.core.ConfigurationManager;
import org.kegbot.core.Flow;
import org.kegbot.core.FlowManager;
import org.kegbot.core.FlowMeter;
import org.kegbot.core.HardwareManager;
import org.kegbot.core.KegbotCore;
import org.kegbot.core.SyncManager;
import org.kegbot.core.Tap;
import org.kegbot.core.TapManager;
import org.kegbot.core.ThermoSensor;
import org.kegbot.proto.Api.RecordTemperatureRequest;
import org.kegbot.proto.Models.KegTap;
import org.kegbot.proto.Models.User;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Primary service for running this kegbot.
 *
 * @author mike wakerly (mike@wakerly.com)
 */
public class KegbotCoreService extends Service {

  private static String TAG = KegbotCoreService.class.getSimpleName();

  private static final int NOTIFICATION_FOREGROUND = 1;

  private KegbotCore mCore;
  private FlowManager mFlowManager;
  private TapManager mTapManager;
  private ConfigurationManager mConfigManager;
  private PreferenceHelper mPreferences;

  private HardwareManager mHardwareManager;
  private SyncManager mApiManager;

  private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

  private long mLastPourStartUptimeMillis = 0;

  private static final long SUPPRESS_POUR_START_MILLIS = 2000;

  private final OnSharedPreferenceChangeListener mPreferenceListener = new OnSharedPreferenceChangeListener() {
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
      if (PreferenceHelper.KEY_API_KEY.equals(key) || PreferenceHelper.KEY_RUN_CORE.equals(key)) {
        Log.d(TAG, "Shared prefs changed, relaunching core; key=" + key);
        updateFromPreferences();
      }
    }
  };

  private final Runnable mFlowManagerWorker = new Runnable() {
    @Override
    public void run() {
      Log.i(TAG, "Kegbot core starting up!");

      try {
        configure();
      } catch (KegbotApiException e1) {
        Log.e(TAG, "Api failed.", e1);
      }
    }
  };

  private final HardwareManager.Listener mHardwareListener = new HardwareManager.Listener() {
    @Override
    public void onTokenRemoved(AuthenticationToken token, String tapName) {
      Log.d(TAG, "Auth token removed: " + token);
    }

    @Override
    public void onTokenAttached(final AuthenticationToken token, final String tapName) {
      Log.d(TAG, "Auth token added: " + token);
      final Intent intent = KegtabBroadcast.getAuthBeginIntent(token);
      sendBroadcast(intent);

      final Runnable r = new Runnable() {
        @Override
        public void run() {
          boolean success = false;
          String message = "";
          try {
            Log.d(TAG, "onTokenAttached: running");
            final AuthenticationManager am = mCore.getAuthenticationManager();
            User user = am.authenticateToken(token);
            Log.d(TAG, "Authenticated user: " + user);
            if (user != null) {
              success = true;
              am.authenticateUser(user);
              for (final Tap tap : mTapManager.getTaps()) {
                mFlowManager.activateUserAtTap(tap, user.getUsername());
              }
            } else {
              message = getString(R.string.authenticating_no_access_token);
            }
          } catch (Exception e) {
            Log.e(TAG, "Exception: " + e, e);
            message = getString(R.string.authenticating_connection_error);
          }
          if (!success) {
            sendBroadcast(KegtabBroadcast.getAuthFailIntent(token, message));
          }
        }
      };
      mExecutorService.submit(r);
    }

    @Override
    public void onThermoSensorUpdate(final ThermoSensor sensor) {
      final Runnable r = new Runnable() {
        @Override
        public void run() {
          Log.d(TAG, "Sensor update for sensor: " + sensor);
          final RecordTemperatureRequest request = RecordTemperatureRequest.newBuilder()
              .setSensorName(sensor.getName()).setTempC((float) sensor.getTemperatureC())
              .buildPartial();
          mApiManager.recordTemperatureAsync(request);
        }
      };
      mExecutorService.submit(r);
    }

    @Override
    public void onMeterUpdate(final FlowMeter meter) {
      final Runnable r = new Runnable() {
        @Override
        public void run() {
          Log.d(TAG, "Meter update for meter: " + meter);
          mFlowManager.handleMeterActivity(meter.getName(), (int) meter.getTicks());
        }
      };
      mExecutorService.submit(r);
    }
  };

  private final FlowManager.Listener mFlowListener = new FlowManager.Listener() {
    @Override
    public void onFlowUpdate(final Flow flow) {
      if (flow.isAuthenticated()) {
        mHardwareManager.setTapRelayEnabled(flow.getTap(), true);
      }
      final Runnable r = new Runnable() {
        @Override
        public void run() {
          Log.d(TAG, "Flow updated: " + flow);
          final Intent intent = KegtabBroadcast.getPourUpdateBroadcastIntent(flow);
          sendOrderedBroadcast(intent, null);
        }
      };
      mExecutorService.submit(r);
    }

    @Override
    public void onFlowStart(final Flow flow) {
      if (flow.isAuthenticated()) {
        mHardwareManager.setTapRelayEnabled(flow.getTap(), true);
      }

      final long now = SystemClock.uptimeMillis();
      final long delta = now - mLastPourStartUptimeMillis;

      if (delta > SUPPRESS_POUR_START_MILLIS) {
        mLastPourStartUptimeMillis = now;
        final Runnable r = new Runnable() {
          @Override
          public void run() {
            Log.d(TAG, "Flow started: " + flow);
            final Intent intent = KegtabBroadcast.getPourStartBroadcastIntent(flow);
            sendOrderedBroadcast(intent, null);
          }
        };
        mExecutorService.submit(r);
      }
    }

    @Override
    public void onFlowEnd(final Flow flow) {
      mHardwareManager.setTapRelayEnabled(flow.getTap(), false);
      final Runnable r = new Runnable() {
        @Override
        public void run() {
          Log.d(TAG, "Flow ended: " + flow);
          recordDrinkForFlow(flow);
        }
      };
      mExecutorService.submit(r);
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "onCreate()");
    mCore = KegbotCore.getInstance(this);
    mTapManager = mCore.getTapManager();
    mFlowManager = mCore.getFlowManager();
    mConfigManager = mCore.getConfigurationManager();
    mApiManager = mCore.getSyncManager();
    mHardwareManager = mCore.getHardwareManager();

    mPreferences = new PreferenceHelper(getApplicationContext());

    mFlowManager.addFlowListener(mFlowListener);

    mExecutorService.execute(mFlowManagerWorker);

    updateFromPreferences();

    mCore.start();

    PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
        .registerOnSharedPreferenceChangeListener(mPreferenceListener);
  }

  /**
   * Binder interface to this service. Local binds only.
   */
  public class LocalBinder extends Binder {
    public KegbotCoreService getService() {
      return KegbotCoreService.this;
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;  // Not bindable.
  }

  @Override
  protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    mCore.dump(writer);
  }

  @Override
  public void onDestroy() {
    Log.d(TAG, "onDestroy()");

    mFlowManager.removeFlowListener(mFlowListener);
    PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
        .unregisterOnSharedPreferenceChangeListener(mPreferenceListener);
    mCore.stop();

    super.onDestroy();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    return START_STICKY;
  }

  public static void startService(Context context) {
    final Intent intent = new Intent(context, KegbotCoreService.class);
    context.startService(intent);
  }

  public static void stopService(Context context) {
    final Intent intent = new Intent(context, KegbotCoreService.class);
    context.stopService(intent);
  }

  private void updateFromPreferences() {
    final boolean runCore = mPreferences.getRunCore();
    if (runCore) {
      debugNotice("Running core!");
      mFlowManager.addFlowListener(mFlowListener);
      mHardwareManager.addListener(mHardwareListener);
      startForeground(NOTIFICATION_FOREGROUND, buildForegroundNotification());
    } else {
      debugNotice("Stopping core.");
      mFlowManager.removeFlowListener(mFlowListener);
      mHardwareManager.removeListener(mHardwareListener);
      mCore.stop();
      stopForeground(true);
    }
  }

  private Notification buildForegroundNotification() {
    final Intent intent = new Intent(this, HomeActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    final PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
        PendingIntent.FLAG_CANCEL_CURRENT);
    final Notification notification = new Notification.Builder(this)
        .setOngoing(true)
        .setSmallIcon(R.drawable.icon)
        .setWhen(System.currentTimeMillis())
        .setContentTitle(getString(R.string.kegbot_core_running))
        .setContentIntent(pendingIntent)
        .getNotification();
    return notification;
  }

  /**
   * @param ended
   */
  private void recordDrinkForFlow(final Flow ended) {
    Log.d(TAG, "Recording drink for flow: " + ended);
    Log.d(TAG, "Tap: "  + ended.getTap());
    mApiManager.recordDrinkAsync(ended);
  }

  /**
   * @throws KegbotApiException
   *
   */
  private void configure() throws KegbotApiException {
    Log.d(TAG, "Configuring!");
    final Uri apiUrl = Uri.parse(mPreferences.getApiUrl());

    KegbotApi api = mCore.getApi();
    api.setApiUrl(apiUrl.toString());
    api.setApiKey(mPreferences.getApiKey());

    final List<KegTap> taps = api.getAllTaps();

    Log.d(TAG, "Found " + taps.size() + " tap(s).");
    for (final KegTap tapInfo : taps) {
      Log.d(TAG, "Adding tap: " + tapInfo.getMeterName());
      final Tap tap = new Tap(tapInfo.getName(), tapInfo.getMlPerTick(), tapInfo
          .getMeterName(), tapInfo.getRelayName());
      mTapManager.addTap(tap);
      mConfigManager.setTapDetail(tap.getMeterName(), tapInfo);
    }
  }

  private void debugNotice(String message) {
    Log.d(TAG, message);
    Toast.makeText(KegbotCoreService.this, message, Toast.LENGTH_SHORT).show();
  }

}