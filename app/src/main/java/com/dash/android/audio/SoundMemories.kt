package com.dash.android.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One saved car sound, and when it was saved. */
@Serializable
data class SoundMemory(val savedAt: Long, val car: CarSound)

/**
 * **Audio › Saved** (DASH-AA 1.1.4, Roger 2026-10-08) — the car sound kept in numbered slots, like a car
 * radio's memory buttons, so hours of fine-tuning are never lost to a slip: press a slot to load it, hold
 * it to save. Everything the car sound holds is kept — the speaker layout with its levels and distances,
 * the equaliser, balance, fade, crossover, loudness, surround, speed volume and calls — but not whether
 * Car sound is on, nor the volume.
 *
 * **Loading never loses anything:** what was there before is kept as [undo] first.
 *
 * Each slot is its own small file in DASH's data folder (`files/sound/slot1.json`…), so a setup can be
 * copied to a USB stick as a backup, or to a friend with the same car. Shared code: only `filesDir`.
 */
class SoundMemories(private val dir: File) {

    private val _slots = MutableStateFlow(List(SLOTS) { read(slotFile(it)) })
    /** [SLOTS] slots, each null while empty. */
    val slots: StateFlow<List<SoundMemory?>> = _slots.asStateFlow()

    private val _undo = MutableStateFlow(read(undoFile))
    /** The car sound as it was before the last load. */
    val undo: StateFlow<SoundMemory?> = _undo.asStateFlow()

    fun save(slot: Int, car: CarSound) {
        val m = SoundMemory(System.currentTimeMillis(), car)
        if (write(slotFile(slot), m)) _slots.value = _slots.value.toMutableList().also { it[slot] = m }
    }

    /**
     * What loading [slot] over [current] gives — [current] kept as [undo] first. Null for an empty slot.
     * Car sound stays on or off as it is.
     */
    fun load(slot: Int, current: CarSound): CarSound? {
        val m = _slots.value.getOrNull(slot) ?: return null
        keepForUndo(current)
        return m.car.copy(enabled = current.enabled)
    }

    /** What undoing the last load gives, swapping it with [current] so a second undo goes back again. */
    fun undo(current: CarSound): CarSound? {
        val m = _undo.value ?: return null
        keepForUndo(current)
        return m.car.copy(enabled = current.enabled)
    }

    private fun keepForUndo(current: CarSound) {
        val m = SoundMemory(System.currentTimeMillis(), current)
        if (write(undoFile, m)) _undo.value = m
    }

    private fun slotFile(slot: Int) = File(dir, "slot${slot + 1}.json")
    private val undoFile get() = File(dir, "before-load.json")

    private fun read(f: File): SoundMemory? =
        runCatching { if (f.exists()) json.decodeFromString(SoundMemory.serializer(), f.readText()) else null }
            .onFailure { Log.w(TAG, "could not read ${f.name}: ${it.message}") }.getOrNull()

    /** Written beside, then renamed over, so a slot is never left half-written. */
    private fun write(f: File, m: SoundMemory): Boolean = runCatching {
        dir.mkdirs()
        val tmp = File(dir, ".${f.name}.tmp")
        tmp.writeText(json.encodeToString(SoundMemory.serializer(), m))
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "rename failed" } }
    }.onFailure { Log.w(TAG, "could not save ${f.name}: ${it.message}") }.isSuccess

    companion object {
        const val SLOTS = 5
        private const val TAG = "DashSoundMemories"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}

/** Whether [this] car sound is [other]'s, Car sound's on or off aside — the slot in use. */
fun CarSound.sameSound(other: CarSound) = copy(enabled = false) == other.copy(enabled = false)
