package app.ember.ui

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** How the Fold is being held. */
object Posture {

    /** Half-open like a laptop: where the hinge sits, as fractions of the window's height. */
    data class Tabletop(val hingeTop: Float, val hingeBottom: Float)

    val tabletop = MutableStateFlow<Tabletop?>(null)

    /** For the emulator tests, which can't fold: pretend the hinge is across the middle. */
    val forced = MutableStateFlow(false)

    fun watch(activity: Activity) {
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                    val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    val h = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds.height()
                    tabletop.value = if (
                        fold != null && h > 0 &&
                        fold.state == FoldingFeature.State.HALF_OPENED &&
                        fold.orientation == FoldingFeature.Orientation.HORIZONTAL
                    ) {
                        Tabletop(fold.bounds.top / h.toFloat(), fold.bounds.bottom / h.toFloat())
                    } else {
                        null
                    }
                }
            }
        }
    }
}
