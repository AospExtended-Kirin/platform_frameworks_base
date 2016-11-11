/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.stack;

import android.content.Context;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import java.util.ArrayList;

/**
 * A global state to track all input states for the algorithm.
 */
public class AmbientState {
    private ArrayList<View> mDraggedViews = new ArrayList<View>();
    private int mScrollY;
    private boolean mDimmed;
    private ActivatableNotificationView mActivatedChild;
    private float mOverScrollTopAmount;
    private float mOverScrollBottomAmount;
    private int mShelfIndex = -1;
    private boolean mDark;
    private boolean mHideSensitive;
    private HeadsUpManager mHeadsUpManager;
    private float mStackTranslation;
    private int mLayoutHeight;
    private int mTopPadding;
    private boolean mShadeExpanded;
    private float mMaxHeadsUpTranslation;
    private boolean mDismissAllInProgress;
    private int mLayoutMinHeight;
    private NotificationShelf mShelf;
    private int mZDistanceBetweenElements;
    private int mBaseZHeight;
    private int mMaxLayoutHeight;

    public AmbientState(Context context) {
        reload(context);
    }

    /**
     * Reload the dimens e.g. if the density changed.
     */
    public void reload(Context context) {
        mZDistanceBetweenElements = Math.max(1, context.getResources()
                .getDimensionPixelSize(R.dimen.z_distance_between_notifications));
        mBaseZHeight = (StackScrollAlgorithm.MAX_ITEMS_IN_BOTTOM_STACK + 1)
                * mZDistanceBetweenElements;
    }

    /**
     * @return the basic Z height on which notifications remain.
     */
    public int getBaseZHeight() {
        return mBaseZHeight;
    }

    /**
     * @return the distance in Z between two overlaying notifications.
     */
    public int getZDistanceBetweenElements() {
        return mZDistanceBetweenElements;
    }

    public int getScrollY() {
        return mScrollY;
    }

    public void setScrollY(int scrollY) {
        this.mScrollY = scrollY;
    }

    public void onBeginDrag(View view) {
        mDraggedViews.add(view);
    }

    public void onDragFinished(View view) {
        mDraggedViews.remove(view);
    }

    public ArrayList<View> getDraggedViews() {
        return mDraggedViews;
    }

    /**
     * @param dimmed Whether we are in a dimmed state (on the lockscreen), where the backgrounds are
     *               translucent and everything is scaled back a bit.
     */
    public void setDimmed(boolean dimmed) {
        mDimmed = dimmed;
    }

    /** In dark mode, we draw as little as possible, assuming a black background */
    public void setDark(boolean dark) {
        mDark = dark;
    }

    public void setHideSensitive(boolean hideSensitive) {
        mHideSensitive = hideSensitive;
    }

    /**
     * In dimmed mode, a child can be activated, which happens on the first tap of the double-tap
     * interaction. This child is then scaled normally and its background is fully opaque.
     */
    public void setActivatedChild(ActivatableNotificationView activatedChild) {
        mActivatedChild = activatedChild;
    }

    public boolean isDimmed() {
        return mDimmed;
    }

    public boolean isDark() {
        return mDark;
    }

    public boolean isHideSensitive() {
        return mHideSensitive;
    }

    public ActivatableNotificationView getActivatedChild() {
        return mActivatedChild;
    }

    public void setOverScrollAmount(float amount, boolean onTop) {
        if (onTop) {
            mOverScrollTopAmount = amount;
        } else {
            mOverScrollBottomAmount = amount;
        }
    }

    public float getOverScrollAmount(boolean top) {
        return top ? mOverScrollTopAmount : mOverScrollBottomAmount;
    }

    public int getShelfIndex() {
        return mShelfIndex;
    }

    public void setShelfIndex(int shelfIndex) {
        mShelfIndex = shelfIndex;
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    public float getStackTranslation() {
        return mStackTranslation;
    }

    public void setStackTranslation(float stackTranslation) {
        mStackTranslation = stackTranslation;
    }

    public void setLayoutHeight(int layoutHeight) {
        mLayoutHeight = layoutHeight;
    }

    public float getTopPadding() {
        return mTopPadding;
    }

    public void setTopPadding(int topPadding) {
        mTopPadding = topPadding;
    }

    public int getInnerHeight() {
        return Math.max(Math.min(mLayoutHeight, mMaxLayoutHeight) - mTopPadding, mLayoutMinHeight);
    }

    public boolean isShadeExpanded() {
        return mShadeExpanded;
    }

    public void setShadeExpanded(boolean shadeExpanded) {
        mShadeExpanded = shadeExpanded;
    }

    public void setMaxHeadsUpTranslation(float maxHeadsUpTranslation) {
        mMaxHeadsUpTranslation = maxHeadsUpTranslation;
    }

    public float getMaxHeadsUpTranslation() {
        return mMaxHeadsUpTranslation;
    }

    public void setDismissAllInProgress(boolean dismissAllInProgress) {
        mDismissAllInProgress = dismissAllInProgress;
    }

    public boolean isDismissAllInProgress() {
        return mDismissAllInProgress;
    }

    public void setLayoutMinHeight(int layoutMinHeight) {
        mLayoutMinHeight = layoutMinHeight;
    }

    public void setShelf(NotificationShelf shelf) {
        mShelf = shelf;
    }

    public NotificationShelf getShelf() {
        return mShelf;
    }

    public void setLayoutMaxHeight(int maxLayoutHeight) {
        mMaxLayoutHeight = maxLayoutHeight;
    }
}
