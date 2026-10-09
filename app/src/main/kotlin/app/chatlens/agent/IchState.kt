package app.chatlens.agent

import android.content.Context
import app.chatlens.data.MemoryRepo
import app.chatlens.memory.IchProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Ich profile in memory (for the UI and the prompts) and encrypted on the device. */
object IchState {
    private val _p = MutableStateFlow(IchProfile())
    val profile: StateFlow<IchProfile> = _p
    @Volatile private var loaded = false

    @Synchronized fun ensureLoaded(ctx: Context) {
        if (loaded) return
        _p.value = runCatching { MemoryRepo.get(ctx).loadIch() }.getOrDefault(IchProfile())
        loaded = true
    }

    @Synchronized fun update(ctx: Context, f: (IchProfile) -> IchProfile) {
        ensureLoaded(ctx)
        val n = f(_p.value)
        _p.value = n
        runCatching { MemoryRepo.get(ctx).saveIch(n) }
    }

    @Synchronized fun clear(ctx: Context) {
        _p.value = IchProfile(); loaded = true
        runCatching { MemoryRepo.get(ctx).deleteIch() }
    }

    fun reset() { _p.value = IchProfile(); loaded = true }
}
