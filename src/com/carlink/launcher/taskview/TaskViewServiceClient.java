/*
 * Copyright (C) 2026 The CarLink Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carlink.launcher.taskview;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.carlink.taskview.ICarLinkTaskViewClient;
import com.carlink.taskview.ICarLinkTaskViewHost;
import com.carlink.taskview.ICarLinkTaskViewService;

/**
 * Client of the SystemUI-side {@code CarLinkTaskViewService}.
 *
 * <p>Wraps bind/unbind, death detection and reconnect with exponential backoff. The binder calls
 * are expected to be made from the main thread; SystemUI is a persistent process so the initial
 * bind returns quickly.
 */
public class TaskViewServiceClient {
    private static final String TAG = "CarLinkLauncher";

    /** Intent action of the SystemUI task view service. */
    public static final String ACTION_BIND_TASK_VIEW_SERVICE =
            "com.carlink.taskview.action.BIND_TASK_VIEW_SERVICE";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final long REBIND_INITIAL_DELAY_MS = 1000;
    private static final long REBIND_MAX_DELAY_MS = 15000;

    /** Listener for service availability changes. Called on the main thread. */
    public interface Listener {
        /** The service is bound and ready to create task views. */
        void onServiceReady();

        /** The connection was lost; previously returned hosts are dead. */
        void onServiceGone();
    }

    private final Context mContext;
    private final Listener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private ICarLinkTaskViewService mService;
    private boolean mBound;
    /** Current rebind backoff; reset to {@link #REBIND_INITIAL_DELAY_MS} once connected. */
    private long mRebindDelayMs = REBIND_INITIAL_DELAY_MS;
    /** Single rebind callback instance so a pending retry can be coalesced and cancelled. */
    private final Runnable mRebindRunnable = this::bind;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!mBound) {
                // Stale callback: the framework dispatched it to the main thread before
                // unbind() ran, so the connection it belongs to is already torn down.
                return;
            }
            Log.i(TAG, "task view service connected");
            mService = ICarLinkTaskViewService.Stub.asInterface(service);
            try {
                service.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                // Warn (not error): with a crash-looping SystemUI this repeats on every
                // rebind cycle, same as the bind-failure paths below.
                Log.w(TAG, "task view service died right after connect", e);
                mService = null;
                scheduleRebind();
                return;
            }
            mRebindDelayMs = REBIND_INITIAL_DELAY_MS;
            mListener.onServiceReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "task view service disconnected");
            handleServiceGone();
        }
    };

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "task view service binder died");
            handleServiceGone();
        }
    };

    public TaskViewServiceClient(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
    }

    /** Binds to the SystemUI task view service. */
    public void bind() {
        if (mBound) {
            return;
        }
        Intent intent = new Intent(ACTION_BIND_TASK_VIEW_SERVICE)
                .setPackage(SYSTEMUI_PACKAGE);
        try {
            mBound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            // Thrown instead of returning false when the service exists but is protected by a
            // permission we lack (e.g. a differently-signed SystemUI build); without this catch
            // the hosting activity would crash in onCreate.
            // Warn (not error): the rebind retries would repeat this line forever.
            Log.w(TAG, "no permission to bind task view service", e);
            scheduleRebind();
            return;
        }
        if (!mBound) {
            // The service does not exist (SystemUI without the CarLink patch, or not restarted
            // yet): keep retrying on the backoff schedule; the attempts are cheap and the
            // bridge then comes up on its own once the service appears.
            // Warn (not error): a missing service is recoverable and retried, an endless error
            // stream would drown real failures.
            Log.w(TAG, "failed to bind task view service, will retry");
            scheduleRebind();
        }
    }

    /** Unbinds from the service. After this call no rebind is attempted. */
    public void unbind() {
        mMainHandler.removeCallbacksAndMessages(null);
        if (mBound) {
            mContext.unbindService(mConnection);
            mBound = false;
        }
        if (mService != null) {
            // Otherwise a binderDied() queued from before the unbind could still post a rebind.
            mService.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mService = null;
        }
    }

    /** True when the service is bound and usable. */
    public boolean isReady() {
        return mService != null;
    }

    /**
     * Creates a new task view pair on the SystemUI side and returns its host handle.
     *
     * @throws IllegalStateException if the service is not connected.
     */
    public ICarLinkTaskViewHost createTaskView(ICarLinkTaskViewClient client) {
        if (mService == null) {
            throw new IllegalStateException("task view service is not connected");
        }
        try {
            return mService.createTaskView(client);
        } catch (RemoteException e) {
            // The service process died; onServiceDisconnected will follow.
            throw new IllegalStateException("task view service call failed", e);
        }
    }

    private void handleServiceGone() {
        // binderDied() runs on a binder thread; hop to the main thread so that the listener
        // (which tears down views) is serialized with bind()/unbind().
        mMainHandler.post(() -> {
            if (mService == null && !mBound) {
                // Process death fires both binderDied() and onServiceDisconnected(); the first
                // call handles it. This state is also reached when unbind() ran meanwhile.
                return;
            }
            // Notify only when the service had actually become ready: mService != null tracks
            // exactly "onServiceReady delivered, onServiceGone not yet". Death can also arrive
            // before the first connect ever completed (this runnable ahead of the queued
            // onServiceConnected, which the stale-callback guard there then drops); no hosts
            // exist in that case, so skip the listener but still rebind.
            boolean wasReady = mService != null;
            mService = null;
            if (wasReady) {
                mListener.onServiceGone();
            }
            scheduleRebind();
        });
    }

    private void scheduleRebind() {
        if (mBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "connection already unregistered", e);
            }
            mBound = false;
        }
        // Coalesce concurrent triggers (e.g. binderDied plus a failing rebind attempt for the
        // same death) into a single pending retry.
        mMainHandler.removeCallbacks(mRebindRunnable);
        // Exponential backoff capped at REBIND_MAX_DELAY_MS: a crash-looping or unpatched
        // SystemUI must not be pounded with a bind attempt every second. The delay is reset
        // in onServiceConnected.
        mMainHandler.postDelayed(mRebindRunnable, mRebindDelayMs);
        mRebindDelayMs = Math.min(mRebindDelayMs * 2, REBIND_MAX_DELAY_MS);
    }
}
