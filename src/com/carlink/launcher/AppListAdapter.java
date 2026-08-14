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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter backing the launcher side bar. Pure framework views only (no androidx).
 *
 * <p>The app currently embedded in the main slot is highlighted, mirroring the v1 slot policy.
 */
public class AppListAdapter extends BaseAdapter {
    private final LayoutInflater mInflater;
    private final List<AppInfo> mApps = new ArrayList<>();

    /** Package name of the app currently shown in the main slot, or null. */
    private String mMainSlotPackage;

    public AppListAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
    }

    public void setApps(List<AppInfo> apps) {
        mApps.clear();
        mApps.addAll(apps);
        notifyDataSetChanged();
    }

    /** Sets the package highlighted as the main slot content. */
    public void setMainSlotPackage(String packageName) {
        mMainSlotPackage = packageName;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mApps.size();
    }

    @Override
    public AppInfo getItem(int position) {
        return mApps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = mInflater.inflate(R.layout.item_app, parent, false);
        }
        AppInfo app = getItem(position);
        ((ImageView) view.findViewById(R.id.app_icon)).setImageDrawable(app.icon);
        ((TextView) view.findViewById(R.id.app_label)).setText(app.label);
        boolean inMainSlot = app.packageName.equals(mMainSlotPackage);
        view.setBackgroundResource(inMainSlot
                ? R.drawable.app_item_selected_background
                : android.R.color.transparent);
        return view;
    }
}
