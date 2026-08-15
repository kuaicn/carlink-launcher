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
package com.carlink.taskview;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceControl;

/**
 * Binder API for the server (SystemUI) side of one CarLink task view.
 *
 * <p>Derived from AAOS {@code android.car.app.ICarTaskViewHost}, trimmed to what the CarLink
 * launcher needs. All calls are oneway: the launcher never blocks on SystemUI.
 */
oneway interface ICarLinkTaskViewHost {
    /** Releases the server side resources and removes the embedded task from WM. */
    void release();

    /**
     * Starts an activity into this task view. {@code options} carries the launch display
     * (ActivityOptions.setLaunchDisplayId) so the task lands on the car virtual display.
     */
    void startActivity(in PendingIntent pendingIntent, in Intent fillInIntent,
            in Bundle options, in Rect launchBounds);

    /**
     * Creates a root task on the given display inside this task view.
     *
     * <p>NOTE: kept for API parity with AAOS. The v1 SystemUI host cannot implement this
     * because ShellTaskOrganizer is not exposed to the phone SystemUI process; the server side
     * currently logs and ignores this call. See docs/design.md.
     */
    void createRootTask(int displayId);

    /** Hands the client's surface (a copy of its SurfaceControl) to the server side. */
    void notifySurfaceCreated(in SurfaceControl control);

    /**
     * Updates the WM-side bounds of the client view, in screen coordinates.
     *
     * <p>The bounds are cached for the next startActivity(); once the task exists they are
     * additionally pushed live through TaskViewTransitions, so the WM task bounds (and with
     * them the input region) track the client slot. See docs/design.md.
     */
    void setWindowBounds(in Rect bounds);

    /** Notifies the server that the client surface was destroyed. */
    void notifySurfaceDestroyed();

    /**
     * Sets the visibility of the embedded task.
     *
     * <p>NOTE: v1 no-op on the server side. Task visibility follows the client surface
     * (surface created/destroyed) which is sufficient for the launcher slot model.
     */
    void setTaskVisibility(boolean visible);

    /**
     * Brings the embedded task to the front.
     *
     * <p>NOTE: v1 no-op on the server side. Launcher slots never overlap, so an embedded task
     * is always the frontmost task inside its own slot.
     */
    void showEmbeddedTask();
}
