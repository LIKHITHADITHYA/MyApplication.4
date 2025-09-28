package com.example.myapplication.di

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class) // Lives as long as the application
object AppModule {

    // Context is already provided by Hilt via @ApplicationContext
    // No need for: fun provideApplicationContext

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(@ApplicationContext app: Context): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(app)
    }

    @Provides
    @Singleton
    fun provideSensorManager(@ApplicationContext app: Context): SensorManager {
        return app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    @Provides
    @Singleton
    fun provideNotificationManager(@ApplicationContext app: Context): NotificationManager {
        return app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Provides
    @Singleton
    fun provideLocationManager(@ApplicationContext app: Context): LocationManager {
        return app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @Provides
    @Singleton
    fun provideWifiP2pManager(application: Application): WifiP2pManager? {
        return application.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    @Provides
    @Singleton
    @Named("mainLooper") // Named annotation in case you need other Loopers
    fun provideMainLooper(): Looper {
        return Looper.getMainLooper()
    }

    // Note: P2pCommunicationManager.Factory is automatically provided by Hilt
    // because P2pCommunicationManager uses @AssistedInject and its Factory uses @AssistedFactory.
    // No explicit @Provides method is needed for the factory itself.
}
