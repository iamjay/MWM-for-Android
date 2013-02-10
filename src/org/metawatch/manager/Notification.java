/*****************************************************************************
 *  Copyright (c) 2011 Meta Watch Ltd.                                       *
 *  www.MetaWatch.org                                                        *
 *                                                                           *
 =============================================================================
 *                                                                           *
 *  Licensed under the Apache License, Version 2.0 (the "License");          *
 *  you may not use this file except in compliance with the License.         *
 *  You may obtain a copy of the License at                                  *
 *                                                                           *
 *    http://www.apache.org/licenses/LICENSE-2.0                             *
 *                                                                           *
 *  Unless required by applicable law or agreed to in writing, software      *
 *  distributed under the License is distributed on an "AS IS" BASIS,        *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 *  See the License for the specific language governing permissions and      *
 *  limitations under the License.                                           *
 *                                                                           *
 *****************************************************************************/

/*****************************************************************************
 * Notification.java                                                         *
 * Notification                                                              *
 * Notification watch mode                                                   *
 *                                                                           *
 *                                                                           *
 *****************************************************************************/

package org.metawatch.manager;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import org.metawatch.manager.MetaWatchService.Preferences;
import org.metawatch.manager.MetaWatchService.WatchBuffers;
import org.metawatch.manager.MetaWatchService.WatchType;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.preference.PreferenceManager;

public class Notification {

    private NotificationType currentNotification = null;

    public static final byte REPLAY = 30;
    final static byte NOTIFICATION_NONE = 0;
    final static byte NOTIFICATION_UP = 30;
    final static byte NOTIFICATION_DOWN = 31;
    final static byte NOTIFICATION_DISMISS = 32;

    private int notifyButtonPress = 0;

    private BlockingQueue<NotificationType> notificationQueue = new LinkedBlockingQueue<NotificationType>();
    private ArrayList<NotificationType> notificationHistory = new ArrayList<NotificationType>();
    private NotificationSender notificationSender;
    private ThreadPoolExecutor mExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
    private Future<?> mFuture;
    final static byte NOTIFICATION_HISTORY_SIZE = 15;

    private static Notification mInstance;

    private Notification() {
    }

    public static Notification getInstance() {
	if (mInstance == null)
	    mInstance = new Notification();
	return mInstance;
    }

    public void destroy() {
	stopNotificationSender();
	mInstance = null;
    }

    private void addToNotificationQueue(Context context, NotificationType notification, boolean forceShow) {
	if (MetaWatchService.mIsRunning && MetaWatchService.connectionState != MetaWatchService.ConnectionState.DISCONNECTED && MetaWatchService.connectionState != MetaWatchService.ConnectionState.DISCONNECTING) {
	    if (!forceShow && MetaWatchService.silentMode()) {
		addToHistory(notification);
	    } else {
		notificationQueue.add(notification);
	    }
	}
    }

    private void addToHistory(NotificationType notification) {
	notification.isNew = false;
	notificationHistory.add(0, notification);
	while (notificationHistory.size() > NOTIFICATION_HISTORY_SIZE)
	    notificationHistory.remove(notificationHistory.size() - 1);
    }

    private class NotificationSender implements Runnable {
	private Context context;

	public NotificationSender(Context context) {
	    this.context = context;
	}

	public void run() {
	    int currentNotificationPage = 0;
	    try {
		//The delay is now up here to account for potentially paused states resulting from disconnection
		Thread.sleep(1000);
		
		NotificationType notification;
		if (currentNotification != null) {
		    // Something bad happened while showing the last
		    // notification, show it again.
		    notification = currentNotification;
		} else {
		    notification = notificationQueue.take();
		    currentNotification = notification;
		}
		MetaWatchService.watchState = MetaWatchService.WatchStates.NOTIFICATION;
		MetaWatchService.WatchModes.NOTIFICATION = true;

		if (Preferences.logging)
		    Log.d(MetaWatchStatus.TAG, "Notification:" + notification.description + " @ " + Utils.ticksToText(context, notification.timestamp));

		if (MetaWatchService.watchType == WatchType.DIGITAL) {

		    if (notification.bitmaps != null && notification.bitmaps.length > 0) {
			Protocol.getInstance(context).sendLcdBitmap(notification.bitmaps[0], MetaWatchService.WatchBuffers.NOTIFICATION);
			currentNotificationPage = 0;

			if (Preferences.logging)
			    Log.d(MetaWatchStatus.TAG, "Notification contains " + notification.bitmaps.length + " bitmaps.");

		    } else if (notification.array != null) {
			Protocol.getInstance(context).sendLcdArray(notification.array, MetaWatchService.WatchBuffers.NOTIFICATION);
		    }
		    else if (notification.buffer != null) {
			Protocol.getInstance(context).sendLcdBuffer(notification.buffer, MetaWatchService.WatchBuffers.NOTIFICATION);
		    } else {
			currentNotification = null;
		    }
		    
		    Protocol.getInstance(context).updateLcdDisplay(MetaWatchService.WatchBuffers.NOTIFICATION);
			
		    /*
		     * Wait until the watch shows the notification before starting the timeout.
		     */
		    synchronized (modeChanged) {
			modeChanged.wait(60000);
		    }
		    
		    /*
		     * Ensure we're in NOTIFICATION mode (massive kludge, but it stops the watch rebounding straight out of a notification immediately
		     */
		    Protocol.getInstance(context).updateLcdDisplay(MetaWatchService.WatchBuffers.NOTIFICATION);
		    
		    if (notification.vibratePattern.vibrate)
			Protocol.getInstance(context).vibrate(notification.vibratePattern.on, notification.vibratePattern.off, notification.vibratePattern.cycles);
		    
		    if (Preferences.notifyLight)
			Protocol.getInstance(context).ledChange(true);
		    
		    if (Preferences.logging)
			Log.d(MetaWatchStatus.TAG, "notif bitmap sent from thread");
		} else {
		    Protocol.getInstance(context).sendOledBuffer(notification.oledTop, WatchBuffers.NOTIFICATION, 0, false);
		    Protocol.getInstance(context).sendOledBuffer(notification.oledBottom, WatchBuffers.NOTIFICATION, 1, false);

		    if (notification.vibratePattern.vibrate)
			Protocol.getInstance(context).vibrate(notification.vibratePattern.on, notification.vibratePattern.off, notification.vibratePattern.cycles);

		    if (notification.oledScroll != null) {

			if (Preferences.logging)
			    Log.d(MetaWatchStatus.TAG, "notification.scrollLength = " + notification.scrollLength);

			/*
			 * If requested, let the notification stay on the screen for a few seconds before starting to scroll.
			 */
			SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
			if (sharedPreferences.getBoolean("pauseBeforeScrolling", false)) {
			    if (Preferences.logging)
				Log.d(MetaWatchStatus.TAG, "Pausing before scrolling.");
			    Thread.sleep(3000);
			}

			if (notification.scrollLength >= 240) {

			    Protocol.getInstance(context).sendOledBufferPart(notification.oledScroll, 0, 240, true, false);
			    // wait continue with scroll

			    for (int i = 240; i < notification.scrollLength; i += 80) {
				try {
				    synchronized (scrollRequest) {
					scrollRequest.wait(60000);
				    }
				} catch (InterruptedException e) {
				    e.printStackTrace();
				}

				if (i + 80 >= notification.scrollLength)
				    Protocol.getInstance(context).sendOledBufferPart(notification.oledScroll, i, 80, false, true);
				else
				    Protocol.getInstance(context).sendOledBufferPart(notification.oledScroll, i, 80, false, false);
			    }

			} else if (notification.scrollLength > 0) {

			    int len = notification.scrollLength / 20 + 1;
			    Protocol.getInstance(context).sendOledBufferPart(notification.oledScroll, 0, len * 20, true, true);

			}
		    }

		}

		/* Send button configuration for sticky notifications. */
		if (notification.timeout < 0) {
		    notifyButtonPress = NOTIFICATION_NONE;
		    if (notification.bitmaps != null & notification.bitmaps.length > 1) {
			Protocol.getInstance(context).enableButton(0, 1, NOTIFICATION_UP, MetaWatchService.WatchBuffers.NOTIFICATION); // Right
			// top
			// press
			Protocol.getInstance(context).enableButton(1, 1, NOTIFICATION_DOWN, MetaWatchService.WatchBuffers.NOTIFICATION); // Right
			// middle
			// press
		    }
		    Protocol.getInstance(context).enableButton(2, 1, NOTIFICATION_DISMISS, MetaWatchService.WatchBuffers.NOTIFICATION); // Right
		    // bottom
		    // press
		}

		if (notification.isNew) {
		    addToHistory(notification);
		}

		notification.viewed = true;

		/* Do the timeout and button handling. */
		if (notification.timeout < 0) {
		    long startTicks = System.currentTimeMillis();
		    if (Preferences.logging)
			Log.d(MetaWatchStatus.TAG, "NotificationSender.run(): Notification sent, waiting for dismiss ");

		    int timeout = getStickyNotificationTimeout(context);

		    do {
			try {
			    synchronized (buttonPressed) {
				buttonPressed.wait(timeout);
			    }
			} catch (InterruptedException e) {
			    if (Preferences.logging)
				Log.d(MetaWatchStatus.TAG, "Button wait interrupted - " + e.getMessage());
			    e.printStackTrace();
			}

			if (notifyButtonPress == NOTIFICATION_UP && currentNotificationPage > 0) {
			    currentNotificationPage--;
			    Protocol.getInstance(context).sendLcdBitmap(notification.bitmaps[currentNotificationPage], MetaWatchService.WatchBuffers.NOTIFICATION);
			} else if (notifyButtonPress == NOTIFICATION_DOWN && currentNotificationPage < notification.bitmaps.length - 1) {
			    currentNotificationPage++;
			    Protocol.getInstance(context).sendLcdBitmap(notification.bitmaps[currentNotificationPage], MetaWatchService.WatchBuffers.NOTIFICATION);
			}

			if ((System.currentTimeMillis() - startTicks) > timeout) {
			    notifyButtonPress = NOTIFICATION_DISMISS;
			}

			if (Preferences.logging)
			    Log.d(MetaWatchStatus.TAG, "Displaying page " + currentNotificationPage + " / " + notification.bitmaps.length);

			Protocol.getInstance(context).updateLcdDisplay(MetaWatchService.WatchBuffers.NOTIFICATION);
		    } while (notifyButtonPress != NOTIFICATION_DISMISS);

		    Protocol.getInstance(context).disableButton(0, 0, MetaWatchService.WatchBuffers.NOTIFICATION); // Right
		    // top
		    Protocol.getInstance(context).disableButton(1, 0, MetaWatchService.WatchBuffers.NOTIFICATION); // Right
		    // middle
		    Protocol.getInstance(context).disableButton(2, 0, MetaWatchService.WatchBuffers.NOTIFICATION); // Right
		    // bottom

		    if (Preferences.logging)
			Log.d(MetaWatchStatus.TAG, "NotificationSender.run(): Done sleeping.");

		} else {
		    if (Preferences.logging)
			Log.d(MetaWatchStatus.TAG, "NotificationSender.run(): Notification sent, sleeping for " + notification.timeout + "ms");
		    Thread.sleep(notification.timeout);
		    if (Preferences.logging)
			Log.d(MetaWatchStatus.TAG, "NotificationSender.run(): Done sleeping.");
		}

		// We're done with this notification.
		currentNotification = null;

		if (MetaWatchService.WatchModes.CALL == false) {
		    exitNotification(context);
		}

	    } catch (InterruptedException ie) {
		/* If we've been interrupted, exit gracefully. */
		if (Preferences.logging)
		    Log.d(MetaWatchStatus.TAG, "NotificationSender was interrupted waiting for next notification, exiting.");
	    } catch (Exception e) {
		if (Preferences.logging)
		    Log.e(MetaWatchStatus.TAG, "Exception in NotificationSender: " + e.toString());
	    }
	    try {
		mFuture = mExecutor.submit(notificationSender);
	    } catch(RejectedExecutionException e) {
		e.printStackTrace();
		//Shutting down
	    }
	    // If the service has disconnected this will block until a connection is reestablished or the service is shutdown.
	    MetaWatchService.mPauseQueue.block();
	}

    };

    public synchronized void startNotificationSender(Context context) {
	notificationSender = new NotificationSender(context);
	mExecutor.execute(notificationSender);
    }

    public synchronized void stopNotificationSender() {
	if (mFuture != null)
	    mFuture.cancel(true);
	if (notificationQueue != null)
	    notificationQueue.clear();
	if (notificationHistory != null)
	    notificationHistory.clear();
	if (mExecutor != null)
	    mExecutor.shutdownNow();
	mInstance = null;
    }

    private static final String NOTIFICATION_TIMEOUT_SETTING = "notificationTimeout";
    private static final String DEFAULT_NOTIFICATION_TIMEOUT_STRING = "5";
    private static final int DEFAULT_NOTIFICATION_TIMEOUT = 5;
    private static final String STICKY_NOTIFICATION_TIMEOUT_SETTING = "stickyNotificationTimeout";
    private static final String STICKY_NOTIFICATION_TIMEOUT_STRING = "120";
    private static final int DEFAULT_STICKY_NOTIFICATION_TIMEOUT = 120;
    private static final int NUM_MS_IN_SECOND = 1000;

    public int getDefaultNotificationTimeout(Context context) {
	SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
	String timeoutString = sharedPreferences.getString(NOTIFICATION_TIMEOUT_SETTING, DEFAULT_NOTIFICATION_TIMEOUT_STRING);
	try {
	    int timeout = Integer.parseInt(timeoutString) * NUM_MS_IN_SECOND;
	    return timeout;
	} catch (NumberFormatException nfe) {
	    return DEFAULT_NOTIFICATION_TIMEOUT;
	}
    }

    public int getStickyNotificationTimeout(Context context) {
	SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
	String timeoutString = sharedPreferences.getString(STICKY_NOTIFICATION_TIMEOUT_SETTING, STICKY_NOTIFICATION_TIMEOUT_STRING);
	try {
	    int timeout = Integer.parseInt(timeoutString) * NUM_MS_IN_SECOND;
	    return timeout;
	} catch (NumberFormatException nfe) {
	    return DEFAULT_STICKY_NOTIFICATION_TIMEOUT;
	}
    }

    public Object scrollRequest = new Object();
    public Object buttonPressed = new Object();
    public Object modeChanged = new Object();

    public class NotificationType {
	public NotificationType() {
	    this.timestamp = System.currentTimeMillis();
	}

	Bitmap[] bitmaps;
	int[] array;
	byte[] buffer;

	byte[] oledTop;
	byte[] oledBottom;
	byte[] oledScroll;

	int scrollLength;
	int timeout;

	VibratePattern vibratePattern;
	public String description;
	public long timestamp;
	public boolean isNew = true; // Prevent notification replays from
				     // getting re-added to the recent list
	public boolean viewed = false;
    }

    public static class VibratePattern {

	public static final VibratePattern NO_VIBRATE = new VibratePattern(false, 0, 0, 0);

	boolean vibrate = false;
	int on;
	int off;
	int cycles;

	public VibratePattern(boolean vibrate, int on, int off, int cycles) {
	    this.vibrate = vibrate;
	    this.on = on;
	    this.off = off;
	    this.cycles = cycles;
	}
    }

    public void addTextNotification(Context context, String text, VibratePattern vibratePattern, int timeout) {
	NotificationType notification = new NotificationType();
	notification.bitmaps = new Bitmap[] { Protocol.getInstance(context).createTextBitmap(context, text) };
	notification.timeout = timeout;
	notification.description = "Text: " + text;
	if (vibratePattern == null)
	    notification.vibratePattern = VibratePattern.NO_VIBRATE;
	else
	    notification.vibratePattern = vibratePattern;
	addToNotificationQueue(context, notification, false);
    }

    public void addBitmapNotification(Context context, Bitmap bitmap, VibratePattern vibratePattern, int timeout, String description) {
	addBitmapNotification(context, new Bitmap[] { bitmap }, vibratePattern, timeout, description);
    }

    public void addBitmapNotification(Context context, Bitmap[] bitmaps, VibratePattern vibratePattern, int timeout, String description) {

	if (bitmaps != null) {
	    if (Preferences.logging)
		Log.d(MetaWatchStatus.TAG, "Notification comprised of " + bitmaps.length + " bitmaps - " + description);
	}

	NotificationType notification = new NotificationType();
	notification.bitmaps = bitmaps;
	notification.timeout = timeout;
	if (vibratePattern == null)
	    notification.vibratePattern = VibratePattern.NO_VIBRATE;
	else
	    notification.vibratePattern = vibratePattern;
	notification.description = description;
	addToNotificationQueue(context, notification, false);
    }

    public void addArrayNotification(Context context, int[] array, VibratePattern vibratePattern, String description) {
	NotificationType notification = new NotificationType();
	notification.array = array;

	int notificationTimeout = getDefaultNotificationTimeout(context);
	notification.timeout = notificationTimeout;
	if (vibratePattern == null)
	    notification.vibratePattern = VibratePattern.NO_VIBRATE;
	else
	    notification.vibratePattern = vibratePattern;
	notification.description = description;
	addToNotificationQueue(context, notification, false);

    }

    public void addBufferNotification(Context context, byte[] buffer, VibratePattern vibratePattern, String description) {
	NotificationType notification = new NotificationType();
	notification.buffer = buffer;
	int notificationTimeout = getDefaultNotificationTimeout(context);
	notification.timeout = notificationTimeout;
	if (vibratePattern == null)
	    notification.vibratePattern = VibratePattern.NO_VIBRATE;
	else
	    notification.vibratePattern = vibratePattern;
	notification.description = description;
	addToNotificationQueue(context, notification, false);

    }

    public void addOledNotification(Context context, byte[] top, byte[] bottom, byte[] scroll, int scrollLength, VibratePattern vibratePattern, String description) {
	NotificationType notification = new NotificationType();
	notification.oledTop = top;
	notification.oledBottom = bottom;
	notification.oledScroll = scroll;
	notification.scrollLength = scrollLength;
	int notificationTimeout = getDefaultNotificationTimeout(context);
	notification.timeout = notificationTimeout;
	if (vibratePattern == null)
	    notification.vibratePattern = VibratePattern.NO_VIBRATE;
	else
	    notification.vibratePattern = vibratePattern;
	notification.description = description;
	addToNotificationQueue(context, notification, false);

    }

    private void exitNotification(Context context) {
	if (Preferences.logging)
	    Log.d(MetaWatchStatus.TAG, "Notification.exitNotification()");
	// disable notification mode
	MetaWatchService.WatchModes.NOTIFICATION = false;

	if (MetaWatchService.WatchModes.CALL == true)
	    return;
	else if (MetaWatchService.WatchModes.APPLICATION == true)
	    Application.toApp(context);
	else if (MetaWatchService.WatchModes.IDLE == true)
	    Idle.getInstance().toIdle(context);
    }

    public void replay(Context context) {
	replay(context, lastNotification());
    }

    public void replay(Context context, NotificationType notification) {
	if (Preferences.logging)
	    Log.d(MetaWatchStatus.TAG, "Notification.replay()");
	if (notification != null) {
	    notification.vibratePattern.vibrate = false;
	    notification.isNew = false;
	    addToNotificationQueue(context, notification, true);
	}
    }

    public void buttonPressed(int button) {
	if (Preferences.logging)
	    Log.d(MetaWatchStatus.TAG, "Notification:Button pressed " + button);

	notifyButtonPress = button;
	synchronized (buttonPressed) {
	    buttonPressed.notify();
	}
    }

    public int getQueueLength() {
	return notificationQueue.size();
    }

    public String dumpQueue() {

	StringBuilder builder = new StringBuilder();

	try {
	    if (currentNotification != null) {
		builder.append(" Displaying: ");
		builder.append(currentNotification.description);
		builder.append("\n");
	    }

	    if (notificationQueue.size() > 0) {
		for (NotificationType notification : notificationQueue) {
		    builder.append(" * ");
		    builder.append(notification.description);
		    builder.append("\n");
		}
	    }

	    NotificationType lastNotification = lastNotification();
	    if (lastNotification != null) {
		builder.append("\n Last: ");
		builder.append(lastNotification.description);
		builder.append("\n");
	    }
	    return builder.toString();
	} catch (java.lang.NullPointerException e) {
	}
	return "";
    }

    public NotificationType lastNotification() {
	if (notificationHistory.isEmpty()) {
	    return null;
	} else {
	    return notificationHistory.get(0);
	}
    }

    public ArrayList<NotificationType> history() {
	return notificationHistory;
    }

    public void clearHistory() {
	notificationHistory.clear();
    }

    public boolean isActive() {
	return currentNotification != null;
    }

}
