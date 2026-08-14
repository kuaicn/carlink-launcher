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

import android.app.ActivityManager.RunningTaskInfo;
import android.view.SurfaceControl;

/**
 * Binder API implemented by the client (launcher) side of one CarLink task view; used by the
 * SystemUI host to report task state.
 *
 * <p>Derived from AAOS {@code android.car.app.ICarTaskViewClient}. Compared to AAOS, the bounds
 * and resize-color callbacks are intentionally dropped: the server caches the view bounds from
 * {@code ICarLinkTaskViewHost#setWindowBounds} and applies resize colors locally.
 * All calls are oneway: the SystemUI shell thread never blocks on the launcher.
 */
oneway interface ICarLinkTaskViewClient {
    /** A task has appeared in the task view; {@code leash} is the task's surface control. */
    void onTaskAppeared(in RunningTaskInfo taskInfo, in SurfaceControl leash);

    /** The task has vanished from the task view. */
    void onTaskVanished(in RunningTaskInfo taskInfo);

    /** The running task's info has changed. */
    void onTaskInfoChanged(in RunningTaskInfo taskInfo);
}
