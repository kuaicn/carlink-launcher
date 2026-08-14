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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import com.carlink.taskview.ICarLinkTaskViewClient;
import com.carlink.taskview.ICarLinkTaskViewHost;
import com.carlink.taskview.ICarLinkTaskViewService;

/**
 * Exported service hosting the CarLink task view bridge (path A).
 *
 * <p>Entry point for {@code com.carlink.launcher}: binding is gated by the manifest
 * attribute {@code android:permission="com.carlink.permission.MANAGE_TASK_VIEW"} and every
 * call is re-checked in {@link #ensureManageTaskViewPermission()}, mirroring
 * {@code CarSystemUIProxyImpl.ensureManageSystemUIPermission()} from AAOS.
 *
 * <p>The actual task view management lives in {@link CarLinkTaskViewHost}, a CoreStartable
 * that owns the WMShell-facing dependencies; this service only performs the permission check
 * and delegates.
 */
public class CarLinkTaskViewService extends Service {
    private static final String TAG = "CarLinkTaskViewService";

    /** Intent action clients use to bind. */
    public static final String ACTION_BIND_TASK_VIEW_SERVICE =
            "com.carlink.taskview.action.BIND_TASK_VIEW_SERVICE";

    /** Signature-level permission defined by com.carlink.launcher. */
    private static final String MANAGE_TASK_VIEW_PERMISSION =
            "com.carlink.permission.MANAGE_TASK_VIEW";

    private final ICarLinkTaskViewService.Stub mBinder = new ICarLinkTaskViewService.Stub() {
        @Override
        public ICarLinkTaskViewHost createTaskView(ICarLinkTaskViewClient client) {
            ensureManageTaskViewPermission(CarLinkTaskViewService.this);
            CarLinkTaskViewHost host = CarLinkTaskViewHost.getInstance();
            if (host == null) {
                throw new IllegalStateException("CarLinkTaskViewHost is not started yet");
            }
            return host.createTaskView(client);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Allows calls from within SystemUI itself; otherwise requires the caller to hold
     * {@code com.carlink.permission.MANAGE_TASK_VIEW}.
     */
    public static void ensureManageTaskViewPermission(Context context) {
        if (Binder.getCallingPid() == Process.myPid()) {
            // If called from within SystemUI, allow.
            return;
        }
        if (context.checkCallingPermission(MANAGE_TASK_VIEW_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        throw new SecurityException("requires permission " + MANAGE_TASK_VIEW_PERMISSION);
    }
}
