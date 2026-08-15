/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;

import com.carlink.taskview.ICarLinkTaskViewClient;
import com.carlink.taskview.ICarLinkTaskViewHost;

/**
 * A {@link SurfaceView} that embeds a task whose content is produced by the SystemUI-side
 * task view host.
 *
 * <p>Derived from AAOS {@code android.car.app.RemoteCarTaskView} (car-lib), adapted to the
 * CarLink binder pair {@link ICarLinkTaskViewHost}/{@link ICarLinkTaskViewClient} and to pure
 * framework views (no car-lib, no androidx).
 *
 * <p>Lifecycle: {@link #setHost(ICarLinkTaskViewHost)} once the SystemUI service has created
 * the matching server side; the view is "initialized" once both the host and the surface are
 * available. Activities may only be launched after initialization, mirroring the AAOS
 * ControlledRemoteCarTaskView ordering requirement (the open transition reads the client bounds
 * while preparing the task leash).
 *
 * <p>Touch: this view punches a hole into the host window's touchable region
 * ({@link ViewTreeObserver.OnComputeInternalInsetsListener}), so touches on the embedded task
 * fall through to the task itself. The host activity window must also set
 * {@link android.view.WindowManager.LayoutParams#FLAG_NOT_TOUCH_MODAL}.
 */
public class CarLinkTaskView extends SurfaceView implements SurfaceHolder.Callback,
        ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = "CarLinkLauncher";

    /** Callback for task state changes. All methods are called on the view's (main) thread. */
    public interface Callback {
        /** The host and the surface are both ready; activities can be launched now. */
        default void onInitialized() {}

        /** A task appeared in this task view. */
        default void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {}

        /** The task's info changed. */
        default void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {}

        /** The task vanished; the view should be torn down. */
        default void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {}
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Rect mTmpRect = new Rect();
    private final int[] mTmpLocation = new int[2];
    private final Rect mTmpRootRect = new Rect();

    private ICarLinkTaskViewHost mHost;
    private Callback mCallback;
    private Region mObscuredTouchRegion;
    private boolean mSurfaceCreated;
    private boolean mInitialized;
    private boolean mReleased;
    private ActivityManager.RunningTaskInfo mTaskInfo;

    private final ICarLinkTaskViewClient.Stub mClientStub = new ICarLinkTaskViewClient.Stub() {
        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
            // The shell side already reparents the task leash under the SurfaceControl that was
            // handed over in notifySurfaceCreated(); the client only needs to track state.
            mMainHandler.post(() -> {
                if (mReleased) {
                    return;
                }
                mTaskInfo = taskInfo;
                // The launch-time bounds are only a hint consumed by the open transition; push
                // the authoritative slot bounds now that the task exists, mirroring
                // ControlledRemoteCarTaskView.onTaskAppeared. This is what makes the WM-side
                // input region land on the slot even when the open transition dropped them
                // (e.g. non-resizeable apps demoted to a fullscreen windowing mode).
                updateWindowBounds();
                if (mCallback != null) {
                    mCallback.onTaskAppeared(taskInfo);
                }
            });
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            mMainHandler.post(() -> {
                if (mReleased) {
                    return;
                }
                mTaskInfo = null;
                if (mCallback != null) {
                    mCallback.onTaskVanished(taskInfo);
                }
            });
        }

        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
            mMainHandler.post(() -> {
                if (mReleased) {
                    return;
                }
                mTaskInfo = taskInfo;
                if (mCallback != null) {
                    mCallback.onTaskInfoChanged(taskInfo);
                }
            });
        }
    };

    public CarLinkTaskView(Context context) {
        this(context, null);
    }

    public CarLinkTaskView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
    }

    /** Returns the binder stub handed to the SystemUI host in createTaskView(). */
    public ICarLinkTaskViewClient getClient() {
        return mClientStub;
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    /**
     * Connects this view to its SystemUI-side host. Must be called once, before launching
     * any activity.
     */
    public void setHost(ICarLinkTaskViewHost host) {
        mHost = host;
        if (mSurfaceCreated) {
            // The surface arrived before the host; hand it over now (surfaceCreated() does
            // the same when it comes second).
            notifySurfaceCreated();
        }
        maybeInitialized();
    }

    /** Returns the info of the embedded task, or null if there is none. */
    public ActivityManager.RunningTaskInfo getTaskInfo() {
        return mTaskInfo;
    }

    /** True once both the host and the surface are ready. */
    public boolean isInitialized() {
        return mInitialized;
    }

    /**
     * Launches the given pending intent into this task view. The launch display must be set on
     * {@code options} by the caller; background activity start is allowed by the SystemUI host.
     */
    public void startActivity(PendingIntent pendingIntent, ActivityOptions options) {
        if (!mInitialized || mReleased) {
            Log.w(TAG, "startActivity() before the task view is initialized, ignored");
            return;
        }
        // Hand the current slot rect over as launch bounds (mirrors
        // ControlledRemoteCarTaskView): the WM-side task is then created with the final bounds
        // right away instead of relying solely on the server-side bounds cache. The bounds
        // decide where the input region lands, not just the visible crop.
        Rect launchBounds = null;
        if (getWidth() > 0 && getHeight() > 0) {
            launchBounds = new Rect();
            getBoundsOnScreen(launchBounds);
        }
        // A zero-size view means the slot was never laid out; keep null launch bounds and let
        // the server fall back to its cached bounds (getCurrentBoundsOnScreen()).
        try {
            mHost.startActivity(pendingIntent, null /* fillInIntent */, options.toBundle(),
                    launchBounds);
        } catch (RemoteException e) {
            Log.e(TAG, "exception in startActivity", e);
        }
    }

    /** Brings the embedded task to the front. No-op if there is no task. */
    public void showEmbeddedTask() {
        if (mHost == null || mReleased) {
            return;
        }
        try {
            mHost.showEmbeddedTask();
        } catch (RemoteException e) {
            Log.e(TAG, "exception in showEmbeddedTask", e);
        }
    }

    /** Sets the visibility of the embedded task. */
    public void setTaskVisibility(boolean visible) {
        if (mHost == null || mReleased) {
            return;
        }
        try {
            mHost.setTaskVisibility(visible);
        } catch (RemoteException e) {
            Log.e(TAG, "exception in setTaskVisibility", e);
        }
    }

    /** Updates the WM bounds of the underlying task to the current view bounds. */
    public void updateWindowBounds() {
        if (mHost == null || mReleased) {
            return;
        }
        getBoundsOnScreen(mTmpRect);
        try {
            mHost.setWindowBounds(mTmpRect);
        } catch (RemoteException e) {
            Log.e(TAG, "exception in setWindowBounds", e);
        }
    }

    /**
     * Indicates a region of the view that is not touchable; touches there are kept by the host
     * window instead of falling through to the embedded task.
     */
    public void setObscuredTouchRegion(Region obscuredRegion) {
        mObscuredTouchRegion = obscuredRegion;
    }

    /** Releases this task view and its SystemUI-side resources. */
    public void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        getHolder().removeCallback(this);
        if (mHost != null) {
            try {
                mHost.release();
            } catch (RemoteException e) {
                Log.e(TAG, "exception in release", e);
            }
            mHost = null;
        }
        // The client stub stays referenced by the (remote) host until it processes the release
        // above, and the stub references this view; drop the callback so the chain back to the
        // embedding activity is not kept alive longer than the view itself.
        mCallback = null;
        mTaskInfo = null;
    }

    private void maybeInitialized() {
        if (!mInitialized && mHost != null && mSurfaceCreated) {
            mInitialized = true;
            if (mCallback != null) {
                mCallback.onInitialized();
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceCreated = true;
        notifySurfaceCreated();
        maybeInitialized();
    }

    private void notifySurfaceCreated() {
        if (mHost == null || mReleased) {
            return;
        }
        try {
            // A copy is sent because the host must be able to reparent the task leash under
            // this surface from the SystemUI process; SurfaceControl is parcelable.
            mHost.notifySurfaceCreated(new SurfaceControl(getSurfaceControl(),
                    "carlink-copy"));
        } catch (RemoteException e) {
            Log.e(TAG, "exception in notifySurfaceCreated", e);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        updateWindowBounds();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceCreated = false;
        if (mHost != null) {
            try {
                mHost.notifySurfaceDestroyed();
            } catch (RemoteException e) {
                Log.e(TAG, "exception in notifySurfaceDestroyed", e);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        // Same logic as car-builtin-lib TouchableInsetsProvider: declare the whole window
        // touchable, then subtract this view so touches on the embedded task pass through.
        if (!isVisibleToUser()) {
            return;
        }
        if (inoutInfo.touchableRegion.isEmpty()) {
            inoutInfo.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            View root = getRootView();
            root.getLocationInWindow(mTmpLocation);
            mTmpRootRect.set(mTmpLocation[0], mTmpLocation[1],
                    mTmpLocation[0] + root.getWidth(), mTmpLocation[1] + root.getHeight());
            inoutInfo.touchableRegion.set(mTmpRootRect);
        }
        getLocationInWindow(mTmpLocation);
        mTmpRect.set(mTmpLocation[0], mTmpLocation[1],
                mTmpLocation[0] + getWidth(), mTmpLocation[1] + getHeight());
        inoutInfo.touchableRegion.op(mTmpRect, Region.Op.DIFFERENCE);

        if (mObscuredTouchRegion != null) {
            inoutInfo.touchableRegion.op(mObscuredTouchRegion, Region.Op.UNION);
        }
    }
}
