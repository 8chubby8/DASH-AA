package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty

/**
 * DASH-AA platform shim — the Android-only `preferencesDataStore(name)` property delegate.
 *
 * DataStore's preferences *core* is multiplatform and is the real thing here; only this one-line
 * delegate is Android's. It keeps Android's two guarantees:
 * - **one store per name for the life of the process** — two DataStores on one file corrupt it, which
 *   is why Android's delegate is a singleton and why this one is too;
 * - the file sits where Android would put it relative to app storage
 *   (`files/datastore/<name>.preferences_pb`).
 *
 * The fork's own Android Auto settings declare a delegate with the same name, so they share the
 * single store with upstream's DashPreferences instead of opening a second file.
 */
fun preferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    ReadOnlyProperty { thisRef, _ -> PreferenceStores.get(thisRef, name) }

private object PreferenceStores {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    fun get(context: Context, name: String): DataStore<Preferences> =
        stores.computeIfAbsent(name) {
            PreferenceDataStoreFactory.create(
                produceFile = { File(context.applicationContext.filesDir, "datastore/$name.preferences_pb") },
            )
        }
}
