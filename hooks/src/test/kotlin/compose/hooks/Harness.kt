package compose.hooks

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalView
import android.view.View
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal object UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

/** A ViewModelStoreOwner standing in for a navigation back-stack entry. clear() == pop. */
internal class FakeOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    fun pop() = viewModelStore.clear()
}

/**
 * JVM composition host. `show(owner)` = screen shown (a new composition, like reshow after back or recreate after
 * rotation); `hide()` = composition disposed while the owner lives (navigated away / activity destroyed for rotation).
 */
internal class Host {
    private val clock = BroadcastFrameClock()
    private val job = Job()
    val errors = ArrayList<Throwable>()
    private val scope = CoroutineScope(
        Dispatchers.Unconfined + clock + job + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e },
    )
    private val recomposer = Recomposer(scope.coroutineContext)
    private var composition: Composition? = null

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
    }

    fun show(owner: ViewModelStoreOwner, content: @Composable () -> Unit) {
        hide()
        composition = Composition(UnitApplier, recomposer).also {
            it.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() } }
        }
        frame()
    }

    /**
     * Screen without a ViewModelStoreOwner (Preview / inspection): LocalViewModelStoreOwner falls back to the
     * LocalView tree, and a bare View (android.jar stub, returnDefaultValues) has no owner -> hooks use `remember`.
     */
    fun showWithoutOwner(content: @Composable () -> Unit) {
        hide()
        composition = Composition(UnitApplier, recomposer).also {
            it.setContent { CompositionLocalProvider(LocalView provides View(null)) { content() } }
        }
        frame()
    }

    fun hide() {
        composition?.dispose()
        composition = null
        frame()
    }

    fun frame() {
        Snapshot.sendApplyNotifications()
        clock.sendFrame(System.nanoTime())
    }

    /** write state outside composition, then run one frame */
    fun act(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        frame()
    }

    fun close() {
        hide()
        recomposer.cancel()
        scope.cancel()
    }
}

internal fun gcUntil(cond: () -> Boolean): Boolean {
    repeat(30) {
        System.gc()
        @Suppress("DEPRECATION") System.runFinalization()
        Thread.sleep(20)
        if (cond()) return true
    }
    return false
}
