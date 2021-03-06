package com.example.geofenceapp.util

object Constants {
    const val PERMISSION_LOCATION_REQUEST_CODE = 1
    const val PERMISSION_BACKGROUND_LOCATION_REQUEST_CODE = 2

    const val PREFERENCE_NAME = "geofence_preference"
    const val PREFERENCE_FIRST_LAUNCH = "firstLaunch"
    const val PREFERENCE_DEFAULT_RADIUS = "defaultRadius"
    const val PREFERENCE_DEFAULT_RADIUS_DEFAULT = 500f

    const val DATABASE_TABLE_NAME = "geofence_table"
    const val DATABASE_NAME = "geofence_db"

    const val NOTIFICATION_CHANNEL_ID = "geofence_transition_id"
    const val NOTIFICATION_CHANNEL_NAME = "geofence_notification"
    const val NOTIFICATION_ID = 3

    const val DEFAULT_LATITUDE = 37.12419
    const val DEFAULT_LONGITUDE = -121.98828
}