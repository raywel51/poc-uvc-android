package digital.raywel.uvucamera

import android.app.Application
import timber.log.Timber

class UVUApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())
    }
}