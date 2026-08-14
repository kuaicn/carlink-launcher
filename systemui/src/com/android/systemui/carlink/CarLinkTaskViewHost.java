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

import android.content.Context;
import android.util.Slog;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.wm.shell.taskview.TaskViewFactory;
import com.carlink.taskview.ICarLinkTaskViewClient;
import com.carlink.taskview.ICarLinkTaskViewHost;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * SystemUI-side owner of all CarLink task views (path A bridge host).
 *
 * <p>Started as a CoreStartable so that it lives in the SysUI dagger graph; {@link
 * CarLinkTaskViewService} reaches it through {@link #getInstance()}.
 *
 * <p>Unlike AAOS CarSystemUI, the phone SystemUI dagger graph does not expose
 * ShellTaskOrganizer / TaskViewTransitions / SyncTransactionQueue; the only WMShell task view
 * entry point available here is {@link TaskViewFactory} (bound into SysUIComponent). The
 * factory creates a {@code TaskView} whose {@code TaskViewTaskController} is then driven
 * remotely by {@link CarLinkTaskViewServerImpl}; the {@code TaskView}'s own view part is never
 * attached to any window.
 */
public class CarLinkTaskViewHost implements CoreStartable {
    private static final String TAG = "CarLinkTaskViewHost";

    private static volatile CarLinkTaskViewHost sInstance;

    private final Context mContext;
    private final Executor mMainExecutor;
    private final Optional<TaskViewFactory> mTaskViewFactory;
    private final List<CarLinkTaskViewServerImpl> mServers = new ArrayList<>();

    @Inject
    public CarLinkTaskViewHost(Context context, @Main Executor mainExecutor,
            Optional<TaskViewFactory> taskViewFactory) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mTaskViewFactory = taskViewFactory;
    }

    @Override
    public void start() {
        sInstance = this;
        Slog.i(TAG, "CarLinkTaskViewHost started, taskViewFactory=" + mTaskViewFactory);
    }

    /** Returns the singleton started by SystemUI, or null if SystemUI has not started yet. */
    public static CarLinkTaskViewHost getInstance() {
        return sInstance;
    }

    /**
     * Creates the server side of a new task view pair for the given client. Called from
     * {@link CarLinkTaskViewService} on a binder thread; the returned binder is valid
     * immediately while the underlying TaskView is created asynchronously.
     */
    public ICarLinkTaskViewHost createTaskView(ICarLinkTaskViewClient client) {
        if (mTaskViewFactory.isEmpty()) {
            // TaskViewFactory is absent when WMShell does not run in this process.
            throw new IllegalStateException("TaskViewFactory is not available in SystemUI");
        }
        CarLinkTaskViewServerImpl server =
                new CarLinkTaskViewServerImpl(mContext, mMainExecutor, client, this);
        synchronized (mServers) {
            mServers.add(server);
        }
        server.init(mTaskViewFactory.get());
        return server.getHostImpl();
    }

    /** Removes a released server from the registry. */
    void onServerReleased(CarLinkTaskViewServerImpl server) {
        synchronized (mServers) {
            mServers.remove(server);
        }
    }

    /** CarLink: binds this host into the CoreStartable map. */
    @Module
    public interface StartableModule {
        @Binds
        @IntoMap
        @ClassKey(CarLinkTaskViewHost.class)
        CoreStartable bindCarLinkTaskViewHost(CarLinkTaskViewHost impl);
    }
}
