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
 * <p>Wraps bind/unbind, death detection and a minimal reconnect skeleton. The binder calls are
 * expected to be made from the main thread; SystemUI is a persistent process so the initial bind
 * returns quickly.
 */
public class TaskViewServiceClient {
    private static final String TAG = "CarLinkLauncher";

    /** Intent action of the SystemUI task view service. */
    public static final String ACTION_BIND_TASK_VIEW_SERVICE =
            "com.carlink.taskview.action.BIND_TASK_VIEW_SERVICE";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final long REBIND_DELAY_MS = 1000;

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

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "task view service connected");
            mService = ICarLinkTaskViewService.Stub.asInterface(service);
            try {
                service.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "task view service died right after connect", e);
                mService = null;
                scheduleRebind();
                return;
            }
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
        mBound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (!mBound) {
            Log.e(TAG, "failed to bind task view service");
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
        mService = null;
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
        mService = null;
        mListener.onServiceGone();
        scheduleRebind();
    }

    private void scheduleRebind() {
        // Reconnect skeleton: a plain delayed rebind is enough for v1 because SystemUI is a
        // persistent process that is restarted immediately by the framework.
        if (mBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "connection already unregistered", e);
            }
            mBound = false;
        }
        mMainHandler.postDelayed(this::bind, REBIND_DELAY_MS);
    }
}
