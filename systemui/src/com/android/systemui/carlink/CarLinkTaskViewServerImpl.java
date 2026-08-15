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

package com.android.systemui.carlink;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.SurfaceControl;

import com.android.wm.shell.taskview.TaskView;
import com.android.wm.shell.taskview.TaskViewBase;
import com.android.wm.shell.taskview.TaskViewFactory;
import com.carlink.taskview.ICarLinkTaskViewClient;
import com.carlink.taskview.ICarLinkTaskViewHost;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Server side of one CarLink task view pair.
 *
 * <p>Derived from AAOS {@code RemoteCarTaskViewServerImpl}, adapted to the phone SystemUI:
 * AAOS builds a {@code TaskViewTaskController} directly from ShellTaskOrganizer /
 * TaskViewTransitions / SyncTransactionQueue, none of which are exposed to the phone SystemUI
 * dagger graph. Instead this class obtains a {@link TaskView} from {@link TaskViewFactory}
 * (the only WMShell task view binding available in SysUIComponent), replaces its
 * {@link TaskViewBase} with this bridge and drives the controller with the client's remote
 * surface. The {@code TaskView}'s own view is never attached to a window, so its surface
 * callbacks never fire.
 *
 * <p>Consequences of going through {@link TaskViewFactory} (see docs/design.md):
 * <ul>
 *     <li>{@code createRootTask} is unsupported (needs ShellTaskOrganizer).</li>
 *     <li>{@code setWindowBounds} only caches the bounds; they are consumed by the next
 *         {@code startActivity()} (TaskViewTaskController reads them back through
 *         {@link #getCurrentBoundsOnScreen()} when the task opens). Resizing an already
 *         embedded task needs TaskViewTransitions.setTaskBounds(), which is not reachable.</li>
 *     <li>{@code setTaskVisibility}/{@code showEmbeddedTask} are no-ops: task visibility
 *         follows the client surface, and launcher slots never overlap.</li>
 * </ul>
 *
 * <p>Threading: binder calls arrive on binder threads and are posted to the main executor;
 * they are queued (bounded, see {@link #MAX_PENDING_OPS}) while the asynchronous
 * {@link TaskViewFactory#create} is in flight. {@link TaskViewBase} callbacks arrive on the
 * shell executor and are forwarded to the (oneway) client binder directly. The client binder
 * is linked to death: a client that dies without {@code release()} triggers the same
 * idempotent release path on the main executor.
 */
public class CarLinkTaskViewServerImpl implements TaskViewBase {
    private static final String TAG = "CarLinkTaskViewServerImpl";

    /**
     * Maximum number of binder calls queued while the asynchronous {@link TaskViewFactory#create}
     * is in flight. The queue only lives for the duration of a thread hop (binder -> shell ->
     * main executor), so a full queue means a flooded or stalled caller; excess ops are dropped.
     */
    private static final int MAX_PENDING_OPS = 32;

    private final Context mContext;
    private final Executor mMainExecutor;
    private final ICarLinkTaskViewClient mClient;
    private final CarLinkTaskViewHost mHost;
    private final int mOwnerUid;
    private final Rect mLastBounds = new Rect();
    private final List<Consumer<TaskView>> mPendingOps = new ArrayList<>();

    private TaskView mTaskView;
    private boolean mReleased;

    /**
     * Releases this server when the client process dies without calling {@code release()},
     * otherwise the TaskView, its ShellTaskOrganizer listener registration and the embedded
     * task would leak. AAOS relies on the car service wrapper for this; this bridge owns the
     * client binder directly, so it links to death itself.
     */
    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Slog.w(TAG, "Task view client died; releasing the server side");
            mMainExecutor.execute(CarLinkTaskViewServerImpl.this::releaseInternal);
        }
    };

    private final ICarLinkTaskViewHost.Stub mHostImpl = new ICarLinkTaskViewHost.Stub() {
        // Every method re-checks the permission, mirroring AAOS RemoteCarTaskViewServerImpl:
        // the host binder can be handed to a third process by the client, so createTaskView()'s
        // check alone is not sufficient. The check MUST stay the first statement of each method
        // (on the binder thread, before any hop to the main executor): once posted, the calling
        // identity is gone and the myPid() bypass in ensureManageTaskViewPermission() would
        // silently let any caller through.
        @Override
        public void release() {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            mMainExecutor.execute(CarLinkTaskViewServerImpl.this::releaseInternal);
        }

        @Override
        public void notifySurfaceCreated(SurfaceControl control) {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            postToTaskView(taskView -> taskView.getController().surfaceCreated(control));
        }

        @Override
        public void setWindowBounds(Rect bounds) {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            postToTaskView(taskView -> {
                synchronized (mLastBounds) {
                    mLastBounds.set(bounds);
                }
            });
        }

        @Override
        public void notifySurfaceDestroyed() {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            postToTaskView(taskView -> taskView.getController().surfaceDestroyed());
        }

        @Override
        public void startActivity(PendingIntent pendingIntent, Intent fillInIntent,
                Bundle options, Rect launchBounds) {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            postToTaskView(taskView -> {
                ActivityOptions opt = ActivityOptions.fromBundle(options);
                if (opt == null) {
                    opt = ActivityOptions.makeBasic();
                }
                // Needed for the pending intent to work under BAL hardening.
                opt.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
                taskView.startActivity(pendingIntent, fillInIntent, opt, launchBounds);
            });
        }

        @Override
        public void createRootTask(int displayId) {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            // Not supported: creating a root task requires ShellTaskOrganizer, which is not
            // exposed to the phone SystemUI process. RootTaskMediator from AAOS is therefore
            // not ported; the launcher only embeds regular activity tasks in v1.
            Slog.w(TAG, "createRootTask is not supported by the CarLink task view host");
        }

        @Override
        public void setTaskVisibility(boolean visible) {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            // No-op: task visibility follows the client surface (created/destroyed), which
            // covers the launcher slot model.
        }

        @Override
        public void showEmbeddedTask() {
            CarLinkTaskViewService.ensureManageTaskViewPermission(mContext);
            // No-op: launcher slots never overlap, so an embedded task is always frontmost
            // inside its own slot.
        }
    };

    public CarLinkTaskViewServerImpl(Context context, Executor mainExecutor,
            ICarLinkTaskViewClient client, CarLinkTaskViewHost host, int ownerUid) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mClient = client;
        mHost = host;
        mOwnerUid = ownerUid;
        try {
            client.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            // The client died between createTaskView() and here; nothing will ever call
            // release(), so schedule it ourselves. Safe before init(): the TaskView creation
            // callback re-checks mReleased and releases the fresh TaskView immediately.
            Slog.w(TAG, "client already dead at createTaskView; scheduling release", e);
            mMainExecutor.execute(this::releaseInternal);
        }
    }

    /** Starts the asynchronous TaskView creation. */
    void init(TaskViewFactory taskViewFactory) {
        taskViewFactory.create(mContext, mMainExecutor, taskView -> {
            synchronized (mPendingOps) {
                if (mReleased) {
                    taskView.release();
                    return;
                }
                mTaskView = taskView;
            }
            // Redirect the controller callbacks (task appeared/vanished, bounds query) from
            // the TaskView's own view to this remote bridge.
            taskView.getController().setTaskViewBase(this);
            List<Consumer<TaskView>> ops;
            synchronized (mPendingOps) {
                ops = new ArrayList<>(mPendingOps);
                mPendingOps.clear();
            }
            for (Consumer<TaskView> op : ops) {
                op.accept(taskView);
            }
        });
    }

    public ICarLinkTaskViewHost getHostImpl() {
        return mHostImpl;
    }

    /** The uid that created this server, captured on the binder thread at creation time. */
    int getOwnerUid() {
        return mOwnerUid;
    }

    /**
     * Idempotent release; must run on the main executor (where every other TaskView
     * interaction happens). Invoked by the client via {@code release()} and by the death
     * recipient when the client process dies.
     */
    private void releaseInternal() {
        synchronized (mPendingOps) {
            if (mReleased) {
                Slog.w(TAG, "TaskView server part already released");
                return;
            }
            mReleased = true;
            mPendingOps.clear();
        }
        mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
        if (mTaskView != null) {
            // Unlike AAOS (explicit removeTask), TaskView#release alone only
            // unregisters the controller; removeTask() removes the embedded task
            // from WM first.
            mTaskView.removeTask();
            mTaskView.release();
        }
        mHost.onServerReleased(this);
    }

    /**
     * Runs the op on the main executor once the TaskView exists; calls arriving before
     * {@link #init} completes are queued in order, up to {@link #MAX_PENDING_OPS}.
     */
    private void postToTaskView(Consumer<TaskView> op) {
        mMainExecutor.execute(() -> {
            synchronized (mPendingOps) {
                if (mReleased) {
                    return;
                }
                if (mTaskView == null) {
                    if (mPendingOps.size() >= MAX_PENDING_OPS) {
                        Slog.w(TAG, "Dropping op: too many calls queued before the TaskView "
                                + "is ready");
                        return;
                    }
                    mPendingOps.add(op);
                    return;
                }
            }
            op.accept(mTaskView);
        });
    }

    // TaskViewBase. Called on the shell executor.

    @Override
    public Rect getCurrentBoundsOnScreen() {
        synchronized (mLastBounds) {
            return new Rect(mLastBounds);
        }
    }

    @Override
    public void setResizeBgColor(SurfaceControl.Transaction transaction, int color) {
        // The transaction is built shell-side against the client surface, so applying it
        // locally is equivalent to forwarding it to the client (AAOS does the round-trip
        // only because its client owns the view).
        transaction.apply();
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        try {
            mClient.onTaskAppeared(taskInfo, leash);
        } catch (RemoteException e) {
            Slog.w(TAG, "client died while reporting onTaskAppeared; host will be released", e);
        }
    }

    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        try {
            mClient.onTaskVanished(taskInfo);
        } catch (RemoteException e) {
            Slog.w(TAG, "client died while reporting onTaskVanished; host will be released", e);
        }
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        try {
            mClient.onTaskInfoChanged(taskInfo);
        } catch (RemoteException e) {
            Slog.w(TAG, "client died while reporting onTaskInfoChanged", e);
        }
    }
}
