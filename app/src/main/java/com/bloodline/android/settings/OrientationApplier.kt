package com.bloodline.android.settings

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * The single place where an [OrientationRequest] becomes an Android constant.
 *
 * [OrientationController] stays free of the framework so its rules can be
 * tested on the JVM; this object is the only thing that knows what the
 * platform calls them.
 */
object OrientationApplier {

    fun toActivityInfo(request: OrientationRequest): Int = when (request) {
        OrientationRequest.FOLLOW_DEVICE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        OrientationRequest.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun apply(activity: Activity, request: OrientationRequest) {
        activity.requestedOrientation = toActivityInfo(request)
    }
}
