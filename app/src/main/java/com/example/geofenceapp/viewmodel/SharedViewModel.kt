package com.example.geofenceapp.viewmodel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.*
import com.example.geofenceapp.broadcastreceiver.GeofenceBroadcastReceiver
import com.example.geofenceapp.data.*
import com.example.geofenceapp.util.Constants.DEFAULT_LATITUDE
import com.example.geofenceapp.util.Constants.DEFAULT_LONGITUDE
import com.example.geofenceapp.util.Constants.PREFERENCE_DEFAULT_RADIUS_DEFAULT
import com.example.geofenceapp.util.ExtensionFunctions.observeOnce
import com.example.geofenceapp.util.Permissions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FindCurrentPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.maps.android.SphericalUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.sqrt

@HiltViewModel
class SharedViewModel @Inject constructor(application: Application,
private val dataStoreRepository: DataStoreRepository,
private val geofenceRepository: GeofenceRepository
): AndroidViewModel(application){
    val app = application
    var _currentLocation = MutableLiveData(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)) //TODO: Start directions from custom popup fom original marker
    val currentLocation get()=_currentLocation

    private var geofencingClient = LocationServices.getGeofencingClient(app.applicationContext)

    var geoCountryCode = ""
    var defaultLocality: String = " "
    var defaultLocalityId: String = " "
    var geoName = "Default" //TODO MAKE BETTER
    var geoId = 0L

    var geoLocationName: String = "Search a city"
    var geoLatLng: LatLng = LatLng(0.0, 0.0)
    var geoRadius: Float = 500f

    var geoCitySelected: Boolean = false
    var geoFenceReady: Boolean = false
    private var _geoFencePrepared = MutableLiveData(false)
    val geoFencePrepared get()=_geoFencePrepared

    var geoSnapshot: Bitmap? = null

    var geofenceRemoved = false

    fun resetSharedValues(){
        geoId = 0
        geoName = "Default Geofence"
        geoCountryCode=""
        geoLocationName = "Search a location"
        geoLatLng=LatLng(0.0, 0.0)
        geoRadius = 500f
        geoSnapshot = null
        geoCitySelected = false
        geoFenceReady = false
        geoFencePrepared.value = false
        geofenceRemoved = false
    }
    //Places API
    @SuppressLint("MissingPermission")
    fun getCountryCodeFromCurrentLocation(placesClient: PlacesClient, geoCoder: Geocoder) {
        viewModelScope.launch {
            val placeFields = listOf(Place.Field.LAT_LNG, Place.Field.ID)
            val request: FindCurrentPlaceRequest = FindCurrentPlaceRequest.newInstance(placeFields)

            val placeResponse = placesClient.findCurrentPlace(request)
            placeResponse.addOnCompleteListener{
                if (it.isSuccessful){
                    val response = it.result
                    val latLng = response.placeLikelihoods[0].place.latLng!!
                    val address = geoCoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                    geoCountryCode = address[0].countryCode
                    Log.d("Step1Fragment", "address[0].locality is ${address[0].locality}")
                    defaultLocality = address[0].getAddressLine(0) ?: " "
                    defaultLocalityId = response.placeLikelihoods[0].place.id ?:" "
                } else {
                    val exception = it.exception
                    if (exception is ApiException){
                        Log.e("Step1Fragment", exception.statusCode.toString())
                    }
                }
            }

        }
    }
    @SuppressLint("MissingPermission")
    fun setVariablesForPreset(
        geoCoder: Geocoder,
        location: LatLng,
    ) {
        viewModelScope.launch {
            val address = geoCoder.getFromLocation(location.latitude, location.longitude, 1)
            if (address != null){
                geoId = System.currentTimeMillis()
                geoName = if (address[0].subLocality!=null){
                    val x = "Geofence " + address[0].subLocality
                    if (x.length > 25)
                        x.substring(0, 25)
                    else
                        x
                } else if(address[0].locality!=null){
                    val x = "Geofence " + address[0].locality
                    Log.d("SharedViewModel","x is $x")
                    if (x.length > 25)
                        x.substring(0, 25)
                    else
                        x
                } else {
                    "Unknown Geofence"
                }
                geoCountryCode= address[0].countryCode
                geoLocationName = if(address[0].subLocality!= null)
                    address[0].subLocality
                    else if (address[0].locality!=null)
                        address[0].locality
                    else
                        "Unknown location"
                Log.d("SharedViewModel", "geoLocationname is $geoLocationName")
                geoLatLng= location
                geoRadius = getDefaultRadius()
                geoCitySelected = true
                geoFenceReady = false
                geofenceRemoved = false

                geoFencePrepared.value = true //Must be done for observeOnce to work
            } else {
                geoFencePrepared.value = false
            }
        }
    }
    //DataStore
    val readFirstLaunch: LiveData<Boolean> = dataStoreRepository.readFirstLaunch.asLiveData()

    fun saveFirstLaunch (firstLaunch: Boolean) = viewModelScope.launch (Dispatchers.IO) {
        dataStoreRepository.saveFirstLaunch(firstLaunch)
    }
    val readDefaultRadius: LiveData<Float> = dataStoreRepository.readDefaultRadius.asLiveData()

    fun saveDefaultRadius (defaultRadius: Float) = viewModelScope.launch (Dispatchers.IO) {
        dataStoreRepository.saveDefaultRadius(defaultRadius)
    }
    private fun getDefaultRadius(): Float{
        Log.d("SharedViewModel", "readDefaultRadius: ${readDefaultRadius.value}")
       return  readDefaultRadius.value?: PREFERENCE_DEFAULT_RADIUS_DEFAULT
    }

    //Database
    val readGeofences: LiveData<MutableList<GeofenceEntity>> = geofenceRepository.readGeofences.asLiveData()
    var geofenceById: LiveData<GeofenceEntity>? = null
    var readGeofencesWithQuery: LiveData<MutableList<GeofenceEntity>>? = null

     fun readGeofencesWithQuery(currentGeoId: Long){
        viewModelScope.launch (Dispatchers.IO) {
            readGeofencesWithQuery = geofenceRepository.readGeofencesWithQuery(currentGeoId).asLiveData()
        }
    }
    fun insertGeofence (geofenceEntity: GeofenceEntity){
        viewModelScope.launch (Dispatchers.IO) {
            geofenceRepository.insertGeofence(geofenceEntity)
        }
    }

    fun deleteGeofence (geofenceEntity: GeofenceEntity){
        viewModelScope.launch (Dispatchers.IO){
            geofenceRepository.deleteGeofence(geofenceEntity)
        }
    }

    fun updateGeofenceName(obj: GeofenceUpdateName) {
        viewModelScope.launch (Dispatchers.IO){
            geofenceRepository.updateGeofenceName(obj)
        }
    }
    fun updateGeofenceEnters(obj: GeofenceUpdateEnters) {
        viewModelScope.launch (Dispatchers.IO){
            geofenceRepository.updateGeofenceEnters(obj)
        }
    }
    fun updateGeofenceDwells(obj: GeofenceUpdateDwells) {
        viewModelScope.launch (Dispatchers.IO){
            geofenceRepository.updateGeofenceDwells(obj)
        }
    }


    fun getGeofenceById(id: Long) {
        viewModelScope.launch (Dispatchers.IO) {
            geofenceById = geofenceRepository.getGeofenceById(id)
        }
    }

    private fun setPendingIntent(geoId: Int): PendingIntent{
        val intent = Intent(app, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            app,
            geoId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    @SuppressLint("MissingPermission")
    fun startGeofence(
        latitude: Double,
        longitude: Double
    ) {
        if (Permissions.hasBackgroundLocationPosition(app)){
            val geofence = Geofence.Builder()
                .setRequestId(geoId.toString())
                .setCircularRegion(
                    latitude,
                    longitude,
                    geoRadius
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_EXIT
                    or Geofence.GEOFENCE_TRANSITION_DWELL
                    or Geofence.GEOFENCE_TRANSITION_ENTER
                )
                .setLoiteringDelay(5000) //TODO increase
                .build()
            val geofencingRequest = GeofencingRequest.Builder()
                .setInitialTrigger(
                    GeofencingRequest.INITIAL_TRIGGER_DWELL
                    or GeofencingRequest.INITIAL_TRIGGER_ENTER
                    or GeofencingRequest.INITIAL_TRIGGER_EXIT
                )
                .addGeofence(geofence)
                .build()

            geofencingClient.addGeofences(geofencingRequest, setPendingIntent(geoId.toInt())).run{
                addOnSuccessListener {
                    Log.d("SharedViewModel" , "successfully STARTED geofence")
                }
                addOnCanceledListener {
                    Log.d("Geofence", "failed adding geofence")
                }
            }
        } else {
            Log.d("Geofence", "Permission not granted")
        }
    }

    suspend fun stopGeofence(geoIds: List<Long>): Boolean{
        return if (Permissions.hasBackgroundLocationPosition(app)){
            val result = CompletableDeferred<Boolean>()
            geofencingClient.removeGeofences(setPendingIntent(geoIds.first().toInt()))
                .addOnCompleteListener {
                    if (it.isSuccessful){
                        Log.d("SharedViewModel", "successfully STOPPED geofence")
                        result.complete(true)
                    } else {
                        result.complete(false)
                    }
                }
            result.await()
        } else {
            false
        }
    }

    fun getBounds(center: LatLng, radius: Float): LatLngBounds {
        val southwestPoint = getSouthwest(center, radius)
        val northeastCorner = getNortheast(center, radius)
        return LatLngBounds(southwestPoint, northeastCorner)
    }
    fun getSouthwest(center: LatLng, radius: Float): LatLng{
        val distanceFromCenterToCorner = radius * sqrt(2.0)
        return SphericalUtil.computeOffset(center, distanceFromCenterToCorner, 225.0)
    }
    fun getNortheast(center: LatLng, radius: Float): LatLng{
        val distanceFromCenterToCorner = radius * sqrt(2.0)
        return SphericalUtil.computeOffset(center, distanceFromCenterToCorner, 45.0)
    }
     fun addGeofenceToDatabase(location: LatLng) {
        val geofenceEntity = GeofenceEntity (
            geoId,
            geoName,
            geoLocationName,
            location.latitude,
            location.longitude,
            geoRadius,
            geoSnapshot,
            0,
            0
        )
        insertGeofence(geofenceEntity)
    }


    @SuppressLint("MissingPermission")
    fun getCurrentLocation(activity: Activity){
        val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(activity)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location : Location? ->
                // Got last known location. In some rare situations this can be null.
                if (location!=null) {
                    Log.d("MapsFragment", "location is not nill: ${location.latitude} ${location.longitude}")
                    currentLocation.value = LatLng(location.latitude, location.longitude)
                }
            }
    }

    fun checkDeviceLocationSettings(context: Context): Boolean{
        return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isLocationEnabled
        } else {
            val mode: Int = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            mode != Settings.Secure.LOCATION_MODE_OFF
        }
    }
}