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
import java.util.Objects;

/**
 * Adapter backing the launcher side bar. Pure framework views only (no androidx).
 *
 * <p>The apps currently embedded in the main / secondary slots are highlighted, mirroring
 * the v1 slot policy.
 */
public class AppListAdapter extends BaseAdapter {
    private final LayoutInflater mInflater;
    private final List<AppInfo> mApps = new ArrayList<>();

    /** Package names of the apps currently embedded in the two slots, or null. */
    private String mMainSlotPackage;
    private String mSecondarySlotPackage;

    public AppListAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
    }

    public void setApps(List<AppInfo> apps) {
        mApps.clear();
        mApps.addAll(apps);
        notifyDataSetChanged();
    }

    /** Sets the packages highlighted as the embedded (main / secondary slot) content. */
    public void setSlotPackages(String mainSlotPackage, String secondarySlotPackage) {
        if (Objects.equals(mMainSlotPackage, mainSlotPackage)
                && Objects.equals(mSecondarySlotPackage, secondarySlotPackage)) {
            // updateLayout() re-pushes the slot state on every transition, including the
            // embed-failure fallbacks where nothing changed; skip the full rebind then.
            return;
        }
        mMainSlotPackage = mainSlotPackage;
        mSecondarySlotPackage = secondarySlotPackage;
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
        ViewHolder holder;
        if (view == null) {
            view = mInflater.inflate(R.layout.item_app, parent, false);
            holder = new ViewHolder();
            holder.icon = view.findViewById(R.id.app_icon);
            holder.label = view.findViewById(R.id.app_label);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }
        AppInfo app = getItem(position);
        holder.icon.setImageDrawable(app.icon);
        holder.label.setText(app.label);
        boolean embedded = app.packageName.equals(mMainSlotPackage)
                || app.packageName.equals(mSecondarySlotPackage);
        view.setBackgroundResource(embedded
                ? R.drawable.app_item_selected_background
                : android.R.color.transparent);
        return view;
    }

    /** Cached child view lookups for a recycled row. */
    private static final class ViewHolder {
        ImageView icon;
        TextView label;
    }
}
