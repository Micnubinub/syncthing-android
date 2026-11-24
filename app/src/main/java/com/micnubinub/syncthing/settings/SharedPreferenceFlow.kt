package com.micnubinub.syncthing.settings

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.edit
import com.micnubinub.syncthing.service.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.MapPreferences
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import java.util.concurrent.atomic.AtomicReference

/**
 * The library assumes we will have a global preference flow and use it everywhere
 * so they use GlobalScope for the flow collection and writing to disk.
 * But since we are doing scoped migration, we need to scope it to the settings activity for now
 * and also implement flow update on external updates.
 * The libraries flow also calls SharedPreferences.clear before writing everything back.
 * These quirks may pose a problem when the app is in the hybrid phase (i.e using SharedPreferences
 * directly and preference flow).
 * We may use the libraries implementation once we can use the preference flow everywhere (i.e.
 * every ui element is reactive with compose).
 */

private fun createMutablePreferenceFlow(
    sharedPreferences: SharedPreferences,
    scope: CoroutineScope,
): MutableStateFlow<Preferences> {
    val flow = MutableStateFlow(sharedPreferences.preferences)

    /**
     * The last map this flow wrote, so its own write coming back through the change
     * listener is recognized and ignored.
     */
    val lastWritten = AtomicReference<Map<String, Any>?>(null)

    /**
     * The map the disk is known to hold. Values that arrive from the disk are recorded
     * here before they reach [flow], so they are never taken for edits and written back.
     * Both coroutines below run on the main thread, so no further locking is needed.
     */
    var previousMap: Map<String, Any> = flow.value.asMap()

    // Sync from Flow -> SharedPreferences
    scope.launch(Dispatchers.Main.immediate) {
        // collect rather than collectLatest: a write-behind whose in-flight write is
        // cancelled leaves the disk holding neither the old nor the new value.
        flow.drop(1).collect { newPrefs ->
            val newMap = newPrefs.asMap()
            // Only the keys this flow actually changed may be written. Diffing the whole
            // map against a disk snapshot and writing the differences back would revert
            // every preference another component changed directly in between the two
            // reads, and would delete keys the flow has never heard of.
            val changed = newMap.keys.filter { newMap[it] != previousMap[it] }
            val removed = previousMap.keys.filter { it !in newMap }
            previousMap = newMap
            if (changed.isEmpty() && removed.isEmpty()) {
                return@collect
            }
            // we restart the app after updating verbose logs setting
            // so commit to disk before ending current process
            val commit = Constants.PREF_VERBOSE_LOG in changed ||
                    Constants.PREF_VERBOSE_LOG in removed
            // Set before the write: the change listener may report the echo back while the
            // write is still in flight.
            lastWritten.set(newMap)
            withContext(Dispatchers.IO) {
                sharedPreferences.edit(commit) {
                    changed.forEach { key -> putAny(key, newMap[key]) }
                    removed.forEach { key -> remove(key) }
                }
            }
        }
    }

    // Sync from SharedPreferences -> Flow (External/Background updates)
    scope.launch(Dispatchers.Main.immediate) {
        sharedPreferences.observeChanges().collect { diskMap ->
            if (diskMap == lastWritten.get()) {
                return@collect
            }
            val currentFlowMap = flow.value.asMap()
            if (diskMap != currentFlowMap) {
                previousMap = diskMap
                flow.value = MapPreferences(diskMap)
            }
        }
    }

    return flow
}

@Composable
fun ProvideSharedPreferenceLocals(
    sharedPreferences: SharedPreferences,
    scope: CoroutineScope,
    content: @Composable () -> Unit,
) {
    val flow = remember(sharedPreferences, scope) {
        createMutablePreferenceFlow(sharedPreferences, scope)
    }
    ProvidePreferenceLocals(flow = flow, content = content)
}

private val SharedPreferences.preferences: Preferences
    get() = MapPreferences(preferenceSnapshot())

private fun SharedPreferences.preferenceSnapshot(): Map<String, Any> =
    all.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

private fun SharedPreferences.observeChanges() = callbackFlow {
    // The listener only signals. Snapshots are then taken one after another off the main
    // thread, so an older snapshot can never arrive after a newer one.
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        trySend(Unit)
    }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}.conflate().map { preferenceSnapshot() }.flowOn(Dispatchers.IO)

private fun SharedPreferences.Editor.putAny(key: String, value: Any?) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        null -> remove(key)
    }
}
