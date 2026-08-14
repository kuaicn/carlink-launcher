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
package com.carlink.taskview;

import com.carlink.taskview.ICarLinkTaskViewClient;
import com.carlink.taskview.ICarLinkTaskViewHost;

/**
 * Entry point of the SystemUI-side task view bridge (CarLinkTaskViewService).
 *
 * <p>Mirrors the role of AAOS {@code ICarSystemUIProxy#createCarTaskView}, trimmed down to a
 * single method: each call creates one server-side task view instance bound to the given client.
 */
interface ICarLinkTaskViewService {
    /**
     * Creates a new task view pair (server side in SystemUI, client side in the caller) and
     * returns the host handle used to drive it. One handle maps to exactly one embedded task.
     */
    ICarLinkTaskViewHost createTaskView(in ICarLinkTaskViewClient client);
}
