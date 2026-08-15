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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
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
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    /** Reloads the side bar app list when packages are installed, removed or changed. */
    private final BroadcastReceiver mPackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

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

        // Keep the side bar in sync with (un)installs while this UI is alive. These are
        // protected system broadcasts, so the flagless overload is exempt from the
        // Android 14 receiver-exported requirement; context-registered (not via the
        // manifest) so package changes do not start the process when the UI is gone.
        IntentFilter packageChanges = new IntentFilter();
        packageChanges.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageChanges.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageChanges.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageChanges.addDataScheme("package");
        registerReceiver(mPackageChangeReceiver, packageChanges);

        loadApps();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mPackageChangeReceiver);
        releaseSlot(mMainSlot);
        releaseSlot(mSecondarySlot);
        mServiceClient.unbind();
        mLoadExecutor.shutdown();
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
            // In-tree privapp (platform_apis, minSdk = platform), so the API 33
            // ResolveInfoFlags overload can be used directly.
            List<ResolveInfo> resolved =
                    pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0));
            List<AppInfo> apps = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : resolved) {
                String pkg = info.activityInfo.packageName;
                if (getPackageName().equals(pkg)) {
                    continue; // Do not offer embedding ourselves.
                }
                if (!seen.add(pkg)) {
                    continue; // One row per package: slots track packages, not activities.
                }
                apps.add(new AppInfo(pkg, info.loadLabel(pm), info.loadIcon(pm)));
            }
            mMainHandler.post(() -> {
                // The query may finish after onDestroy (the executor is only shut down
                // there); never push results into a dead UI.
                if (!isDestroyed()) {
                    mAppListAdapter.setApps(apps);
                }
            });
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
            // The caller may have just released this slot for replacement; keep the layout
            // and the side bar highlight in sync with the actual slot state.
            updateLayout();
            return;
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(app.packageName);
        if (launchIntent == null) {
            Log.w(TAG, "no launch intent for " + app.packageName);
            updateLayout();
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
                if (slot.taskView != taskView || slot.pendingLaunch == null) {
                    // Stale callback: the slot was cleared or reused while this task view
                    // was initializing (same identity guard as onTaskVanished below), so
                    // this embed must not launch anything into the slot's new owner.
                    return;
                }
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
                if (slot.taskView != taskView) {
                    // Stale callback: the slot was already cleared or reused while this
                    // binder call was in flight (e.g. queued right before the host died).
                    return;
                }
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
            // Same as the early returns above: restore layout/highlight consistency after
            // a failed replacement (the slot may have just been released by the caller).
            updateLayout();
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
