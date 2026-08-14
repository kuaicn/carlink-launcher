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

package com.carlink.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.carlink.launcher.taskview.CarLinkTaskView;
import com.carlink.launcher.taskview.TaskViewServiceClient;
import com.carlink.taskview.ICarLinkTaskViewHost;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Car head-unit launcher running inside the virtual display created by
 * {@code com.carlink.interconnect}.
 *
 * <p>Layout: a side bar with the installed launchable apps on the left, and up to two content
 * slots (main / secondary) on the right, each embedding a third-party activity through the
 * SystemUI task view bridge (path A, see docs/design.md).
 *
 * <p>v1 slot policy when an app icon is tapped:
 * <ul>
 *     <li>app already embedded in a slot: bring it to front (host.showEmbeddedTask);</li>
 *     <li>main slot empty: embed into the main slot;</li>
 *     <li>secondary slot empty: embed into the secondary slot;</li>
 *     <li>both occupied: replace the main slot (its task view is released); secondary is kept.</li>
 * </ul>
 */
public class LauncherActivity extends Activity implements TaskViewServiceClient.Listener {
    private static final String TAG = "CarLinkLauncher";

    /** One content slot: a container view plus, while occupied, a CarLinkTaskView. */
    private static final class Slot {
        final FrameLayout container;
        CarLinkTaskView taskView;
        String packageName;
        /** App launch pending while the task view initializes; consumed in onInitialized. */
        PendingIntent pendingLaunch;

        Slot(FrameLayout container) {
            this.container = container;
        }

        boolean isOccupied() {
            return packageName != null;
        }
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Executor mLoadExecutor = Executors.newSingleThreadExecutor();

    private TaskViewServiceClient mServiceClient;
    private AppListAdapter mAppListAdapter;
    private Slot mMainSlot;
    private Slot mSecondarySlot;
    private View mEmptyHint;
    private int mDisplayId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Required so that touches on the embedded tasks (which live outside this window) are
        // routed there instead of being grabbed by this window.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        setContentView(R.layout.activity_main);

        // This activity itself runs on the car virtual display, so the display id is known
        // locally and no IPC is needed to obtain it.
        mDisplayId = getDisplay() != null ? getDisplay().getDisplayId() : -1;
        Log.i(TAG, "onCreate on display " + mDisplayId);

        mMainSlot = new Slot(findViewById(R.id.main_container));
        mSecondarySlot = new Slot(findViewById(R.id.secondary_container));
        mEmptyHint = findViewById(R.id.empty_hint);

        mAppListAdapter = new AppListAdapter(this);
        ListView appList = findViewById(R.id.app_list);
        appList.setAdapter(mAppListAdapter);
        appList.setOnItemClickListener((parent, view, position, id) ->
                onAppClicked(mAppListAdapter.getItem(position)));

        mServiceClient = new TaskViewServiceClient(this, this);
        mServiceClient.bind();

        loadApps();
    }

    @Override
    protected void onDestroy() {
        releaseSlot(mMainSlot);
        releaseSlot(mSecondarySlot);
        mServiceClient.unbind();
        super.onDestroy();
    }

    // TaskViewServiceClient.Listener

    @Override
    public void onServiceReady() {
        Log.i(TAG, "task view service ready");
    }

    @Override
    public void onServiceGone() {
        // The SystemUI side died; all existing hosts are dead. Tear the slots down locally;
        // the user can re-embed apps once the service is back (rebind is automatic).
        Log.w(TAG, "task view service gone, clearing slots");
        clearSlot(mMainSlot, false /* releaseHost */);
        clearSlot(mSecondarySlot, false /* releaseHost */);
        updateLayout();
    }

    private void loadApps() {
        mLoadExecutor.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);
            List<AppInfo> apps = new ArrayList<>();
            for (ResolveInfo info : resolved) {
                String pkg = info.activityInfo.packageName;
                if (getPackageName().equals(pkg)) {
                    continue; // Do not offer embedding ourselves.
                }
                apps.add(new AppInfo(pkg, info.loadLabel(pm), info.loadIcon(pm)));
            }
            mMainHandler.post(() -> mAppListAdapter.setApps(apps));
        });
    }

    private void onAppClicked(AppInfo app) {
        // v1 slot policy (see class javadoc).
        if (app.packageName.equals(mMainSlot.packageName)) {
            showEmbeddedTask(mMainSlot);
            return;
        }
        if (app.packageName.equals(mSecondarySlot.packageName)) {
            showEmbeddedTask(mSecondarySlot);
            return;
        }
        if (!mMainSlot.isOccupied()) {
            embed(mMainSlot, app);
        } else if (!mSecondarySlot.isOccupied()) {
            embed(mSecondarySlot, app);
        } else {
            releaseSlot(mMainSlot);
            embed(mMainSlot, app);
        }
    }

    private void showEmbeddedTask(Slot slot) {
        if (slot.taskView != null) {
            slot.taskView.showEmbeddedTask(); // v1: no-op server side, slots never overlap.
        }
    }

    /**
     * Embeds the given app into the slot. The actual activity start is deferred until the task
     * view reports initialized (host bound + surface created).
     */
    private void embed(Slot slot, AppInfo app) {
        if (!mServiceClient.isReady()) {
            Toast.makeText(this, R.string.toast_service_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(app.packageName);
        if (launchIntent == null) {
            Log.w(TAG, "no launch intent for " + app.packageName);
            return;
        }

        CarLinkTaskView taskView = new CarLinkTaskView(this);
        slot.taskView = taskView;
        slot.packageName = app.packageName;
        slot.pendingLaunch = PendingIntent.getActivity(this, 0 /* requestCode */, launchIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        taskView.setCallback(new CarLinkTaskView.Callback() {
            @Override
            public void onInitialized() {
                // The launch display rides in the options bundle all the way into
                // TaskViewTransitions, so the new task is created on the car virtual display.
                ActivityOptions options = ActivityOptions.makeBasic();
                if (mDisplayId >= 0) {
                    options.setLaunchDisplayId(mDisplayId);
                }
                taskView.startActivity(slot.pendingLaunch, options);
            }

            @Override
            public void onTaskVanished(android.app.ActivityManager.RunningTaskInfo taskInfo) {
                Log.i(TAG, "task vanished in slot: " + taskInfo.taskId);
                clearSlot(slot, true /* releaseHost */);
                updateLayout();
            }
        });

        ICarLinkTaskViewHost host;
        try {
            host = mServiceClient.createTaskView(taskView.getClient());
        } catch (IllegalStateException e) {
            Log.e(TAG, "failed to create task view", e);
            slot.taskView = null;
            slot.packageName = null;
            slot.pendingLaunch = null;
            return;
        }
        taskView.setHost(host);

        slot.container.addView(taskView);
        updateLayout();
    }

    /** Releases the task view of an occupied slot and empties it. */
    private void releaseSlot(Slot slot) {
        clearSlot(slot, true /* releaseHost */);
    }

    private void clearSlot(Slot slot, boolean releaseHost) {
        if (slot.taskView != null) {
            if (releaseHost) {
                slot.taskView.release();
            }
            slot.container.removeView(slot.taskView);
            slot.taskView = null;
        }
        slot.packageName = null;
        slot.pendingLaunch = null;
    }

    /** Updates container visibility and the side bar highlight to match the slot state. */
    private void updateLayout() {
        mMainSlot.container.setVisibility(
                mMainSlot.isOccupied() ? View.VISIBLE : View.GONE);
        mSecondarySlot.container.setVisibility(
                mSecondarySlot.isOccupied() ? View.VISIBLE : View.GONE);
        boolean anyOccupied = mMainSlot.isOccupied() || mSecondarySlot.isOccupied();
        mEmptyHint.setVisibility(anyOccupied ? View.GONE : View.VISIBLE);
        mAppListAdapter.setMainSlotPackage(mMainSlot.packageName);
    }
}
