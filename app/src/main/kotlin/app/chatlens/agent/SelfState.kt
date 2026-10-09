package app.chatlens.agent

import android.content.Context
import app.chatlens.data.MemoryRepo
import app.chatlens.memory.IchLogic
import app.chatlens.memory.IchProfile
import app.chatlens.memory.SelfCodec
import app.chatlens.memory.SelfPartial
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Partial results and the self-analysis proposal, stored encrypted (so a run can resume after cancellation). */
object SelfState {
    private val _proposal = MutableStateFlow<IchProfile?>(null)
    val proposal: StateFlow<IchProfile?> = _proposal
    private val _partials = MutableStateFlow<List<SelfPartial>>(emptyList())
    val partials: StateFlow<List<SelfPartial>> = _partials

    @Synchronized fun load(ctx: Context) {
        val s = runCatching { MemoryRepo.get(ctx).loadSelf() }.getOrNull() ?: return
        _partials.value = s.first; _proposal.value = s.second
    }

    @Synchronized fun addPartial(ctx: Context, p: SelfPartial) {
        _partials.value = _partials.value + p
        persist(ctx)
    }

    @Synchronized fun setProposal(ctx: Context, p: IchProfile?) { _proposal.value = p; persist(ctx) }

    @Synchronized fun clear(ctx: Context) {
        _partials.value = emptyList(); _proposal.value = null
        runCatching { MemoryRepo.get(ctx).deleteSelf() }
    }

    private fun persist(ctx: Context) {
        runCatching { MemoryRepo.get(ctx).saveSelf(SelfCodec.partialsToJson(_partials.value, _proposal.value)) }
    }

    /** Confirmation by the user: adopt the proposal into the Ich profile. Pinned entries and deletions made by the user stay untouched. */
    fun confirm(ctx: Context, blocked: Set<String>) {
        val p = _proposal.value ?: return
        IchState.update(ctx) { IchLogic.adopt(it, p, blocked, System.currentTimeMillis()) }
        clear(ctx)
    }
}
