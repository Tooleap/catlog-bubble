package com.nolanlawson.logcat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.nolanlawson.logcat.data.LogLine;
import com.nolanlawson.logcat.helper.SaveLogHelper;
import com.nolanlawson.logcat.helper.ServiceHelper;
import com.nolanlawson.logcat.helper.WidgetHelper;
import com.nolanlawson.logcat.util.UtilLogger;

/**
 * Reads logs.
 * 
 * @author nolan
 * 
 */
public class LogcatRecordingService extends IntentService {

	private static final Class<?>[] mStartForegroundSignature = new Class[] {
	    int.class, Notification.class};
	private static final Class<?>[] mStopForegroundSignature = new Class[] {
	    boolean.class};
	
	private static final String ACTION_STOP_RECORDING = "com.nolanlawson.catlog.action.STOP_RECORDING";
	public static final String URI_SCHEME = "catlog_recording_service";
	
	private static UtilLogger log = new UtilLogger(LogcatRecordingService.class);

	private Process logcatProcess;

	private NotificationManager mNM;
	private Method mStartForeground;
	private Method mStopForeground;
	private Object[] mStartForegroundArgs = new Object[2];
	private Object[] mStopForegroundArgs = new Object[1];
	
	private String filename;
	private BroadcastReceiver receiver = new BroadcastReceiver() {
		
		@Override
		public void onReceive(Context context, Intent intent) {
			log.d("onReceive()");
			
			// received broadcast to kill service
			killProcess();
			ServiceHelper.stopBackgroundServiceIfRunning(context);
		}
	};
	
	private Handler handler;


	public LogcatRecordingService() {
		super("AppTrackerService");
	}
	

	@Override
	public void onCreate() {
		super.onCreate();
		log.d("onCreate()");
		
		mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		
		IntentFilter intentFilter = new IntentFilter(ACTION_STOP_RECORDING);
		intentFilter.addDataScheme(URI_SCHEME);
		
		registerReceiver(receiver, intentFilter);
		
		handler = new Handler(Looper.getMainLooper());
		
		try {
			mStartForeground = getClass().getMethod("startForeground",
					mStartForegroundSignature);
			mStopForeground = getClass().getMethod("stopForeground",
					mStopForegroundSignature);
		} catch (NoSuchMethodException e) {
			// Running on an older platform.
			log.d(e,"running on older platform; couldn't find startForeground method");
			mStartForeground = mStopForeground = null;
		}

	}


	@Override
	public void onDestroy() {
		log.d("onDestroy()");
		super.onDestroy();
		killProcess();

		unregisterReceiver(receiver);
		
		stopForegroundCompat(R.string.notification_title);
		
		WidgetHelper.updateWidgets(getApplicationContext(), false);
		
		
	}

    // This is the old onStart method that will be called on the pre-2.0
    // platform.
    @Override
    public void onStart(Intent intent, int startId) {
    	log.d("onStart()");
    	super.onStart(intent, startId);
    	filename = intent.getStringExtra("filename");
        handleCommand(intent);
    }

	private void handleCommand(Intent intent) {
        
		// notify the widgets that we're running
		WidgetHelper.updateWidgets(getApplicationContext());
		
        CharSequence tickerText = getText(R.string.notification_ticker);

        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.status_icon, tickerText,
                System.currentTimeMillis());
        

        Intent stopRecordingIntent = new Intent();
        stopRecordingIntent.setAction(ACTION_STOP_RECORDING);
        // have to make this unique for God knows what reason
        stopRecordingIntent.setData(Uri.withAppendedPath(Uri.parse(URI_SCHEME + "://stop/"), 
        		Long.toHexString(new Random().nextLong())));
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                    0 /* no requestCode */, stopRecordingIntent, PendingIntent.FLAG_ONE_SHOT);
        
        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, getText(R.string.notification_title),
                       getText(R.string.notification_subtext), pendingIntent);

        startForegroundCompat(R.string.notification_title, notification);

		
	}


	/**
	 * This is a wrapper around the new startForeground method, using the older
	 * APIs if it is not available.
	 */
	private void startForegroundCompat(int id, Notification notification) {
	    // If we have the new startForeground API, then use it.
	    if (mStartForeground != null) {
	        mStartForegroundArgs[0] = Integer.valueOf(id);
	        mStartForegroundArgs[1] = notification;
	        try {
	            mStartForeground.invoke(this, mStartForegroundArgs);
	        } catch (InvocationTargetException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke startForeground");
	        } catch (IllegalAccessException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke startForeground");
	        }
	        return;
	    }

	    // Fall back on the old API.
	    setForeground(true);
	    mNM.notify(id, notification);
	}

	/**
	 * This is a wrapper around the new stopForeground method, using the older
	 * APIs if it is not available.
	 */
	private void stopForegroundCompat(int id) {
	    // If we have the new stopForeground API, then use it.
	    if (mStopForeground != null) {
	        mStopForegroundArgs[0] = Boolean.TRUE;
	        try {
	            mStopForeground.invoke(this, mStopForegroundArgs);
	        } catch (InvocationTargetException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke stopForeground");
	        } catch (IllegalAccessException e) {
	            // Should not happen.
	            log.d(e, "Unable to invoke stopForeground");
	        }
	        return;
	    }

	    // Fall back on the old API.  Note to cancel BEFORE changing the
	    // foreground state, since we could be killed at that point.
	    mNM.cancel(id);
	    setForeground(false);
	}

	protected void onHandleIntent(Intent intent) {
		log.d("onHandleIntent()");
		handleIntent(intent);
	}
	
	private void handleIntent(Intent intent) {
		
		log.d("Starting up AppTrackerService now with intent: %s", intent);

		makeToast(R.string.log_recording_started, Toast.LENGTH_SHORT);
		
		logcatProcess = null;
		BufferedReader reader = null;
		
		StringBuilder stringBuilder = new StringBuilder();
		
		try {
			
			// use the "time" log so we can see what time the logs were logged at
			logcatProcess = Runtime.getRuntime().exec(
					new String[] { "logcat", "-v", "time" });

			reader = new BufferedReader(new InputStreamReader(logcatProcess
					.getInputStream()));
		
			Date currentDate = new Date(System.currentTimeMillis());
			SimpleDateFormat dateFormat = new SimpleDateFormat(LogLine.LOGCAT_DATE_FORMAT);
			
			String line;
			
			// keep skipping lines until we find one that is past the current time.
			// unfortunately, Android logcat does not print out the year, so if
			// somebody starts this process at January 1st at 12:01am he/she
			// will get the entire log buffer.  But that's not the end of the world.
			
			boolean pastCurrentTime = false;
			
			while ((line = reader.readLine()) != null) {

				if (!pastCurrentTime) {
					
					if (line.length() < 19) { // length of date format at beginning of log line
						continue;
					}
					
					// first get the timestamp
					Date lineDate = null;
					
					try {
						lineDate = dateFormat.parse(line);
					} catch (ParseException e) {
						continue;
					}
					
					if (lineDate == null) {
						continue;
					}
					
					// assume that the date in logcat comes from this year
					lineDate.setYear(currentDate.getYear());
					
					if (lineDate.before(currentDate)) {
						continue;
					} else {
						pastCurrentTime = true;
					}
				}
				
				stringBuilder.append(line).append("\n");
			}

		}

		catch (IOException e) {
			log.e(e, "unexpected exception");
		}

		finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					log.e(e, "unexpected exception");
				}
			}
			
			if (logcatProcess != null) {
				logcatProcess.destroy();
			}

			log.d("CatlogService ended");
			
			boolean logSaved = SaveLogHelper.saveLog(stringBuilder, filename);
			
			if (logSaved) {
				String savedText = String.format(getText(R.string.log_saved_from_recording).toString(),filename);
				makeToast(savedText, Toast.LENGTH_LONG);
				startLogcatActivityToViewSavedFile();
			} else {
				makeToast(R.string.unable_to_save_log, Toast.LENGTH_LONG);
			}
			

		}
	}

	private void startLogcatActivityToViewSavedFile() {
		
		// start up the logcat activity if necessary and show the saved file
		
		Intent targetIntent = new Intent(getApplicationContext(), LogcatActivity.class);
		targetIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NEW_TASK);
		targetIntent.setAction(Intent.ACTION_MAIN);
		targetIntent.putExtra("filename", filename);
		
		startActivity(targetIntent);
		
	}


	private void makeToast(final int stringResId, final int toastLength) {
		handler.post(new Runnable() {
			
			@Override
			public void run() {
				
				Toast.makeText(LogcatRecordingService.this, stringResId, toastLength).show();
				
			}
		});
		
	}
	private void makeToast(final String str, final int toastLength) {
		handler.post(new Runnable() {
			
			@Override
			public void run() {
				
				Toast.makeText(LogcatRecordingService.this, str, toastLength).show();
				
			}
		});
		
	}	
	private void killProcess() {
		// kill the logcat process
		if (logcatProcess != null) {
			logcatProcess.destroy();
		}
	}
	
}