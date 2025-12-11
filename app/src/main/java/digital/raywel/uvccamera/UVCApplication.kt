package digital.raywel.uvccamera

import android.app.Application
import timber.log.Timber

class UVCApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())
    }
}