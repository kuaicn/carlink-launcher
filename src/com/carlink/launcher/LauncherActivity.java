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
import android.content.res.Configuration;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
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
 *
 * <p>Slot selection: exactly one of the two slots is "selected" at any time
 * ({@link #mSelectedSlot}, main by default), shown as a rounded highlight ring around the
 * slot container. The selection moves when a slot is clicked (its padding ring is the only
 * part of an occupied slot that still receives clicks in this window — touches on the
 * content itself fall through to the embedded task) and to the slot a new app is embedded
 * into. When the selected slot is emptied while the other slot is occupied (task vanished,
 * embed watchdog, service loss), the selection moves to the occupied one in
 * {@link #updateLayout()}, so the ring always tracks real content when there is any; with
 * both slots empty it falls back to the main slot.
 */
public class LauncherActivity extends Activity implements TaskViewServiceClient.Listener {
    private static final String TAG = "CarLinkLauncher";

    /**
     * How long to wait for onTaskAppeared after startActivity before giving up on the embed.
     * Covers the broken-embed case (e.g. the shell transition was not claimed by the task
     * view transitions) that would otherwise leave the slot permanently black. The margin
     * must also absorb a slow cold start on a loaded system: a false trigger tears down an
     * embed that would otherwise have succeeded.
     */
    private static final long EMBED_TIMEOUT_MS = 5000;

    /**
     * Delay coalescing a burst of package-change broadcasts into a single side bar reload.
     * One (un)install/update arrives as several broadcasts (ADDED plus CHANGED, REPLACED for
     * an update) and each reload is a full package query plus a full list rebind, so reacting
     * to every broadcast would redraw the list several times for one real change.
     */
    private static final long PACKAGE_RELOAD_DELAY_MS = 300;

    /** One content slot: a container view plus, while occupied, a CarLinkTaskView. */
    private static final class Slot {
        final FrameLayout container;
        CarLinkTaskView taskView;
        String packageName;
        /** App launch pending while the task view initializes; consumed in onInitialized. */
        PendingIntent pendingLaunch;
        /** Embed watchdog posted at launch; cancelled when the task appears or the slot clears. */
        Runnable embedWatchdog;

        Slot(FrameLayout container) {
            this.container = container;
        }

        boolean isOccupied() {
            return packageName != null;
        }
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    /** Single pending reload instance so a broadcast burst can be coalesced and cancelled. */
    private final Runnable mPackageReloadRunnable = this::loadApps;

    /** Reloads the side bar app list when packages are installed, removed or changed. */
    private final BroadcastReceiver mPackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Debounce the burst (see PACKAGE_RELOAD_DELAY_MS). Coalescing also narrows the
            // window in which a tap mid-touch is swallowed: AbsListView drops a pending
            // click when the adapter data changed since the touch, and every reload's
            // rebind counts as a change.
            mMainHandler.removeCallbacks(mPackageReloadRunnable);
            mMainHandler.postDelayed(mPackageReloadRunnable, PACKAGE_RELOAD_DELAY_MS);
        }
    };

    private TaskViewServiceClient mServiceClient;
    private AppListAdapter mAppListAdapter;
    private Slot mMainSlot;
    private Slot mSecondarySlot;
    /**
     * The currently selected slot; never null after onCreate. Invariant: exactly one of the
     * two slots is selected at any time (see the class javadoc).
     */
    private Slot mSelectedSlot;
    private View mEmptyHint;
    private TextView mAppListEmpty;
    /**
     * Corner radii in px: the container outline / selection ring, and the embedded content.
     * Density-dependent, so they are re-resolved in onConfigurationChanged (the manifest
     * handles density changes without recreating the activity).
     */
    private float mSlotCornerRadiusPx;
    private float mSlotContentCornerRadiusPx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Required so that touches on the embedded tasks (which live outside this window) are
        // routed there instead of being grabbed by this window.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        setContentView(R.layout.activity_main);

        // This activity itself runs on the car virtual display, so the display id is known
        // locally and no IPC is needed to obtain it. It is deliberately not cached in a
        // field: embed() queries it at launch time (see there).
        Display display = getDisplay();
        Log.i(TAG, "onCreate on display " + (display != null ? display.getDisplayId() : -1));

        mMainSlot = new Slot(findViewById(R.id.main_container));
        mSecondarySlot = new Slot(findViewById(R.id.secondary_container));
        mSelectedSlot = mMainSlot; // default selection, kept even while both slots are empty.
        mEmptyHint = findViewById(R.id.empty_hint);

        mSlotCornerRadiusPx = getResources().getDimension(R.dimen.slot_corner_radius);
        mSlotContentCornerRadiusPx =
                mSlotCornerRadiusPx - getResources().getDimension(R.dimen.slot_border_padding);
        setupSlotContainer(mMainSlot);
        setupSlotContainer(mSecondarySlot);

        mAppListAdapter = new AppListAdapter(this);
        ListView appList = findViewById(R.id.app_list);
        appList.setAdapter(mAppListAdapter);
        // Placeholder shown while the package query runs (loading text from the layout);
        // loadApps() swaps it for the "no apps" text if the first result is empty.
        mAppListEmpty = findViewById(R.id.app_list_empty);
        appList.setEmptyView(mAppListEmpty);
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

        // Apply the initial selection visuals (both slots empty: the ring lands on the main
        // slot's container, which is still GONE at this point).
        updateLayout();
        loadApps();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mPackageChangeReceiver);
        // Drop a debounced reload still pending on the main handler: it would otherwise run
        // loadApps() after the executor shutdown below and die on RejectedExecutionException.
        mMainHandler.removeCallbacks(mPackageReloadRunnable);
        releaseSlot(mMainSlot);
        releaseSlot(mSecondarySlot);
        mServiceClient.unbind();
        mLoadExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The task may be reparented between displays without being recreated (see
        // android:configChanges): an instance left on the phone display is moved onto the
        // car virtual display when a new session launches it there, and the two displays
        // usually differ in density. The px corner radii resolved in onCreate are then
        // stale: re-resolve them and re-apply — the outline providers read the field when
        // the outline is (re)computed, and the embedded content radius was pushed to the
        // task views once at embed time.
        // The layout adapts on its own and slot bounds are pushed by
        // CarLinkTaskView.surfaceChanged()/onLayout() once the window is resized.
        mSlotCornerRadiusPx = getResources().getDimension(R.dimen.slot_corner_radius);
        mSlotContentCornerRadiusPx =
                mSlotCornerRadiusPx - getResources().getDimension(R.dimen.slot_border_padding);
        mMainSlot.container.invalidateOutline();
        mSecondarySlot.container.invalidateOutline();
        if (mMainSlot.taskView != null) {
            mMainSlot.taskView.setCornerRadius(mSlotContentCornerRadiusPx);
        }
        if (mSecondarySlot.taskView != null) {
            mSecondarySlot.taskView.setCornerRadius(mSlotContentCornerRadiusPx);
        }
    }

    @Override
    public void onBackPressed() {
        // The desktop must stay resident: back is injected into the virtual display and
        // reaches this window whenever no embedded task holds the focus; finishing here
        // would leave the head unit staring at a black display. Back while an embedded app
        // is focused is delivered to that app's window and unaffected by this.
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
        // A package-change broadcast already dispatched to the main thread is still
        // delivered after unregisterReceiver() in onDestroy; its debounce would re-post
        // the reload runnable, which would then hit the shut-down executor and die on
        // RejectedExecutionException. Every caller runs on the main thread (where
        // onDestroy and the executor shutdown also run), so this check is race-free.
        if (isDestroyed()) {
            return;
        }
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
                    if (apps.isEmpty()) {
                        // The query completed: replace the loading placeholder with the
                        // definitive "no apps" text (the list stays on the loading text
                        // until the first result arrives).
                        mAppListEmpty.setText(R.string.app_list_empty);
                    }
                }
            });
        });
    }

    private void onAppClicked(AppInfo app) {
        // v1 slot policy (see class javadoc). The slot the click operates on becomes the
        // selected one: the already-embedded slot when bringing to front, otherwise the slot
        // the app is newly embedded into.
        if (app.packageName.equals(mMainSlot.packageName)) {
            selectSlot(mMainSlot);
            showEmbeddedTask(mMainSlot);
            return;
        }
        if (app.packageName.equals(mSecondarySlot.packageName)) {
            selectSlot(mSecondarySlot);
            showEmbeddedTask(mSecondarySlot);
            return;
        }
        if (!mMainSlot.isOccupied()) {
            if (embed(mMainSlot, app)) {
                selectSlot(mMainSlot);
            }
        } else if (!mSecondarySlot.isOccupied()) {
            if (embed(mSecondarySlot, app)) {
                selectSlot(mSecondarySlot);
            }
        } else {
            // Both slots occupied: replacing the main slot destroys its embed first. When
            // the service is down the new embed cannot start anyway, so check before
            // releasing — the user keeps the running app instead of trading it for an
            // empty slot. embed() re-checks on its own (the service can still die in
            // between); this pre-check only protects the destructive replace path.
            if (!mServiceClient.isReady()) {
                Toast.makeText(this, R.string.toast_service_not_ready,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            releaseSlot(mMainSlot);
            if (embed(mMainSlot, app)) {
                selectSlot(mMainSlot);
            }
        }
    }

    /** Marks the given slot as selected and refreshes the selection visuals. */
    private void selectSlot(Slot slot) {
        if (mSelectedSlot == slot) {
            return;
        }
        mSelectedSlot = slot;
        updateLayout();
    }

    /**
     * Rounds the slot container and wires click-to-select. The outline provider must not
     * derive from the background (ViewOutlineProvider.BACKGROUND): the unselected slot has a
     * null background, which would produce an empty outline and, with clipToOutline, clip the
     * embedded content away entirely.
     *
     * <p>The click listener deliberately lives on the container instead of a touch intercept:
     * touches on the embedded content fall through to the embedded task (the task view punches
     * the window's touchable region), so they must not and cannot be caught here; only touches
     * on the padding ring around the content reach this listener.
     */
    private void setupSlotContainer(Slot slot) {
        FrameLayout container = slot.container;
        container.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        mSlotCornerRadiusPx);
            }
        });
        container.setClipToOutline(true);
        container.setOnClickListener(v -> selectSlot(slot));
    }

    private void showEmbeddedTask(Slot slot) {
        if (slot.taskView != null) {
            slot.taskView.showEmbeddedTask(); // v1: no-op server side, slots never overlap.
        }
    }

    /**
     * Embeds the given app into the slot. The actual activity start is deferred until the task
     * view reports initialized (host bound + surface created).
     *
     * @return false when the embed could not even be set up (service not ready, no launch
     *     intent, host creation failure); the slot is left empty then. Later failures surface
     *     asynchronously through the embed watchdog or onTaskVanished.
     */
    private boolean embed(Slot slot, AppInfo app) {
        if (!mServiceClient.isReady()) {
            Toast.makeText(this, R.string.toast_service_not_ready, Toast.LENGTH_SHORT).show();
            // The caller may have just released this slot for replacement; keep the layout
            // and the side bar highlight in sync with the actual slot state.
            updateLayout();
            return false;
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(app.packageName);
        if (launchIntent == null) {
            // The app was listed but has no launchable entry point left (uninstalled, or
            // its launcher activity disabled, since the list was loaded); the debounced
            // reload drops the row shortly. Give the tap a visible answer instead of
            // failing silently.
            Log.w(TAG, "no launch intent for " + app.packageName);
            Toast.makeText(this, R.string.toast_embed_failed, Toast.LENGTH_SHORT).show();
            updateLayout();
            return false;
        }
        // Always launch a fresh task: reusing an existing (recents or already visible) task
        // produces a TRANSIT_TO_FRONT transition, which TaskViewTransitions only claims on
        // bubble-enabled builds; on this build it would be treated as alien and the slot
        // would stay black. NEW_TASK|MULTIPLE_TASK forces the new-task path that is matched
        // via the launch cookie instead.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        CarLinkTaskView taskView = new CarLinkTaskView(this);
        // Round the corners of the embedded content to match the container ring: the hidden
        // SurfaceView API rounds both the surface layer and the hole punched for it (usable
        // here: in-tree platform-signed privapp, platform_apis build). The radius is the
        // container's minus the padding, so the content arc stays concentric with the ring.
        taskView.setCornerRadius(mSlotContentCornerRadiusPx);
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
                // Queried at launch time rather than cached from onCreate: the task can be
                // reparented between displays without any callback (no configuration change
                // fires when the displays' configurations match, and the display id is not
                // part of the configuration), so a cached id can go stale.
                ActivityOptions options = ActivityOptions.makeBasic();
                Display display = getDisplay();
                if (display != null) {
                    options.setLaunchDisplayId(display.getDisplayId());
                }
                taskView.startActivity(slot.pendingLaunch, options);
                // Black-screen fallback: if the task never appears (the shell side cleaned
                // it up without notifying the client), release the slot instead of leaving
                // it black forever.
                slot.embedWatchdog = () -> onEmbedTimeout(slot, taskView);
                mMainHandler.postDelayed(slot.embedWatchdog, EMBED_TIMEOUT_MS);
            }

            @Override
            public void onTaskAppeared(android.app.ActivityManager.RunningTaskInfo taskInfo) {
                if (slot.taskView != taskView) {
                    // Stale callback, see onTaskVanished below.
                    return;
                }
                cancelEmbedWatchdog(slot);
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
        } catch (IllegalStateException | SecurityException e) {
            // IllegalStateException covers the service racing away, the SystemUI side not
            // being started yet and the per-uid task view limit (server-thrown runtime
            // exceptions are marshalled back through the binder proxy verbatim, so they
            // are not folded into the client wrapper's IllegalStateException either).
            // SecurityException is the server-side permission re-check firing: a build or
            // signature mismatch that retrying cannot fix, hence the different toast.
            Log.e(TAG, "failed to create task view", e);
            slot.taskView = null;
            slot.packageName = null;
            slot.pendingLaunch = null;
            // Same as the early returns above: restore layout/highlight consistency after
            // a failed replacement (the slot may have just been released by the caller).
            updateLayout();
            Toast.makeText(this, e instanceof SecurityException
                    ? R.string.toast_embed_denied : R.string.toast_embed_failed,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        taskView.setHost(host);

        slot.container.addView(taskView);
        updateLayout();
        return true;
    }

    /** Releases the task view of an occupied slot and empties it. */
    private void releaseSlot(Slot slot) {
        clearSlot(slot, true /* releaseHost */);
    }

    private void clearSlot(Slot slot, boolean releaseHost) {
        cancelEmbedWatchdog(slot);
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

    /** Fired when no task appeared within {@link #EMBED_TIMEOUT_MS} of the launch. */
    private void onEmbedTimeout(Slot slot, CarLinkTaskView taskView) {
        if (slot.taskView != taskView) {
            // Stale watchdog: the slot was cleared or reused while it was pending.
            return;
        }
        slot.embedWatchdog = null;
        Log.w(TAG, "no task appeared within " + EMBED_TIMEOUT_MS + "ms for "
                + slot.packageName + ", releasing the slot");
        clearSlot(slot, true /* releaseHost */);
        updateLayout();
        Toast.makeText(this, R.string.toast_embed_failed, Toast.LENGTH_LONG).show();
    }

    private void cancelEmbedWatchdog(Slot slot) {
        if (slot.embedWatchdog != null) {
            mMainHandler.removeCallbacks(slot.embedWatchdog);
            slot.embedWatchdog = null;
        }
    }

    /**
     * Updates container visibility, the selection ring and the side bar highlight to match the
     * slot state. Single funnel for every slot mutation; also maintains the selection
     * invariant (exactly one selected slot, on real content whenever there is any).
     */
    private void updateLayout() {
        if (!mSelectedSlot.isOccupied()) {
            // The selected slot is empty: when the other slot is occupied the selection moves
            // there (e.g. the selected slot's task just vanished), so the ring always tracks
            // real content; when both are empty it falls back to the main slot, keeping the
            // both-empty state deterministic (same as the onCreate default).
            Slot other = mSelectedSlot == mMainSlot ? mSecondarySlot : mMainSlot;
            mSelectedSlot = other.isOccupied() ? other : mMainSlot;
        }
        mMainSlot.container.setVisibility(
                mMainSlot.isOccupied() ? View.VISIBLE : View.GONE);
        mSecondarySlot.container.setVisibility(
                mSecondarySlot.isOccupied() ? View.VISIBLE : View.GONE);
        boolean anyOccupied = mMainSlot.isOccupied() || mSecondarySlot.isOccupied();
        mEmptyHint.setVisibility(anyOccupied ? View.GONE : View.VISIBLE);
        // Resource 0 clears the background: the unselected slot gets no ring.
        mMainSlot.container.setBackgroundResource(mSelectedSlot == mMainSlot
                ? R.drawable.slot_selected_background : 0);
        mSecondarySlot.container.setBackgroundResource(mSelectedSlot == mSecondarySlot
                ? R.drawable.slot_selected_background : 0);
        mAppListAdapter.setSlotPackages(mMainSlot.packageName, mSecondarySlot.packageName);
        mAppListAdapter.setSelectedSlotPackage(mSelectedSlot.packageName);
    }
}
