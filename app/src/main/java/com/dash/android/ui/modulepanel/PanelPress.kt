package com.dash.android.ui.modulepanel

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dash.android.core.ModuleData
import com.dash.android.core.ReportedValue
import com.dash.android.panel.PanelVariable
import com.dash.android.panel.TouchBinding
import com.dash.android.transport.Actions
import kotlinx.coroutines.delay

/**
 * A press that has been sent and is waiting for the module to confirm it (roadmap 1.6.7).
 *
 * **This is intent, not fact**, which is exactly why it lives here and not in [ModuleData]. That
 * store holds what modules have *said*; a prediction is what DASH expects them to say next, and
 * writing one into the store would make the State Inspector report a reading no module ever sent.
 * The two are merged only at the last moment, in the map the panel draws from.
 */
private data class Predicted(val value: String, val sentAt: Long)

/**
 * One module's panel values, and what happens when the user presses a control (roadmap 1.6.7).
 *
 * [values] is what the panel draws: the module's reported variables with any live prediction laid
 * over the top.
 */
class PanelPresses(
    val values: Map<String, String>,
    val press: (TouchBinding) -> Unit,
)

/**
 * The optimistic update, and the timeout that keeps optimism honest (`module-layout.md` §8).
 *
 * **A touch binding's `control` is the variable it predicts, and its `value` is what it predicts.**
 * *(The rule §8 assumes throughout and never states — a control named `preset` carrying `high` is
 * confirmed by `REPORT|id|preset|high`, and the whole reconcile only works because those are the
 * same name. Recorded here because the code now depends on it; it wants writing into §8 before the
 * spec locks at 1.6.10.)*
 *
 * **A control with no value predicts nothing.** That is §8's momentary case falling out of the rule
 * rather than being special-cased: a reset or a "level now" changes no variable the layout is
 * watching, so there is nothing to draw ahead of, nothing to time out, and nothing to revert. Press
 * feedback still fires, because that is local and has nothing to do with the module.
 *
 * **Three outcomes, of which only one is a fault** (§8). The first two need no telling apart: any
 * report of the variable after the press retires the prediction, so a module that complied and a
 * module that heard and could not comply both simply end with the panel drawing what the module
 * actually said. Only silence is a fault.
 */
private class Optimism {

    var predictions by mutableStateOf<Map<String, Predicted>>(emptyMap())
        private set

    /** Consecutive unanswered presses. Reset by any answer, because a fault is a *run* of them. */
    var unanswered by mutableStateOf(0)
        private set

    fun predict(variable: String, value: String, now: Long) {
        predictions = predictions + (variable to Predicted(value, now))
    }

    /**
     * Retire every prediction the module has answered, and time out the rest.
     *
     * **Answered means either the value we predicted or a report we did not have when we pressed.**
     * Both halves are needed, and each covers what the other cannot. The reports desk drops an
     * unchanged value before it reaches the store (1.4.9's change check), so pressing ZERO on a
     * gauge already reading zero produces no new report at all and would time out for ever on a
     * timestamp test alone. And a module that answered with something *else* — §8's middle row —
     * never matches the predicted value, so only the timestamp can show that it spoke.
     *
     * The gap that leaves is honest and small: a press predicting the value a variable already
     * holds is treated as answered whether the module heard it or not. Nothing is drawn wrongly by
     * that (the prediction and the truth agree), and the alternative is a fault counter that fires
     * on presses which changed nothing.
     */
    fun settle(reported: Map<String, ReportedValue>, now: Long) {
        var timedOut = 0
        var answered = 0
        val survivors = predictions.filter { (variable, prediction) ->
            val report = reported[variable]
            val heard = report != null &&
                (report.value == prediction.value || report.updatedAt > prediction.sentAt)
            when {
                heard -> { answered++; false }
                now - prediction.sentAt >= TIMEOUT_MS -> { timedOut++; false }
                else -> true
            }
        }
        if (survivors.size != predictions.size) predictions = survivors

        // A single timeout reverts quietly — a dropped message on a shared bus is not a fault worth
        // interrupting a driver over. A *run* of them is a module that is alive and heartbeating and
        // not honouring actions, which is a different and more sinister failure than one that has
        // simply stopped talking, and is worth surfacing. **How many, and where it surfaces, is an
        // open item in §8 to be tuned against real hardware** — so this counts and says so, and does
        // not yet invent a threshold or a badge to hang on it.
        if (answered > 0) unanswered = 0
        if (timedOut > 0) {
            unanswered += timedOut
            Log.w(TAG, "$timedOut action(s) unanswered within ${TIMEOUT_MS}ms — reverted ($unanswered in a row)")
        }
    }

    companion object {
        const val TAG = "DashPanelPress"

        /**
         * **The timeout measures acknowledgement, not completion** (§8). Raising an air-suspended
         * car takes seconds; confirming that the press was *accepted* is always immediate work, and
         * the achieved heights arrive later as separate variables. So one generous timeout covers
         * every transport, with nothing for an author to declare and nothing to tune.
         */
        const val TIMEOUT_MS = 1500L

        /** How often an outstanding prediction is re-examined. Only ever decides how promptly a
         *  revert happens, so it is comfortably finer than the timeout and costs nothing between. */
        const val TICK_MS = 100L
    }
}

/**
 * The panel's values and its press handler, wired to the module that owns the panel (roadmap 1.6.7).
 *
 * **Keyed by module id, and the id is never consumed** — a `REPORT` variable belongs to one module's
 * panel and nowhere else (1.4.9). Two modules may each have a `temperature`; they are different data
 * in different panels and can never collide. The predictions are keyed to the module for the same
 * reason: swapping panels retires the old one's outstanding presses with it, rather than leaving a
 * prediction to expire against a module that is no longer on screen.
 */
@Composable
fun rememberPanelPresses(
    moduleData: ModuleData,
    actions: Actions,
    moduleId: String?,
    variables: Map<String, PanelVariable> = emptyMap(),
): PanelPresses {
    val all by moduleData.values.collectAsState()
    val optimism = remember(moduleId) { Optimism() }

    val reported = remember(all, moduleId) {
        if (moduleId == null) emptyMap() else all[moduleId].orEmpty()
    }
    val predictions = optimism.predictions

    /*
     * Restarted by every report that lands **and by every press**, then left ticking for as long as
     * anything is outstanding. Those are the only two things that can retire a prediction — an
     * answer, or the clock.
     *
     * **`predictions` is in the key, and leaving it out was a real defect** (found on hardware,
     * 1.6.7: the needle went optimistically to zero with the board unplugged and stayed there for
     * ever). Keyed only on the report map, this effect ran once at composition, found nothing
     * outstanding, fell straight out of the loop and completed — and a press arriving afterwards
     * changed neither key, so nothing ever restarted it. The optimistic write worked; the clock
     * that was supposed to make it honest was never running.
     *
     * The lesson is a general one about `LaunchedEffect`: a loop that polls state must be keyed on
     * the state that decides whether it should be looping, or it only ever answers the question it
     * was asked at composition.
     */
    LaunchedEffect(reported, predictions) {
        if (predictions.isEmpty()) return@LaunchedEffect
        optimism.settle(reported, System.currentTimeMillis())
        while (optimism.predictions.isNotEmpty()) {
            delay(Optimism.TICK_MS)
            optimism.settle(reported, System.currentTimeMillis())
        }
    }

    val values = remember(reported, predictions) {
        reported.mapValues { it.value.value } + predictions.mapValues { it.value.value }
    }

    return PanelPresses(
        values = values,
        press = { binding ->
            if (moduleId != null) {
                /*
                 * **A stepped control works out its value before it sends** (roadmap 1.6.7), which
                 * is what collapses the two kinds of control into one. Up to 1.6.7 a `+` sent a
                 * direction — `fan_up` — and could not be drawn until the module answered, because
                 * nothing in the format expressed "one more than it is now". Now the module's own
                 * board says what the values are and in what order, so DASH lands on the answer
                 * first and sends it as an ordinary set-this-to-this.
                 *
                 * The result is that everything on the wire is now the same message, and §8's rule
                 * — the control names the variable, the value is the prediction — stops being a case
                 * and becomes universal.
                 */
                val landed = if (binding.step == 0) binding.value else
                    variables[binding.control]?.stepFrom(values[binding.control], binding.step)

                /*
                 * **A step that lands nowhere sends nothing** — and this reversed a decision on
                 * contact with the code, which is worth recording rather than quietly doing.
                 *
                 * The intent was to send regardless, on the reasoning that DASH's index is a
                 * *belief* about the module's state and staying quiet would let a stale belief
                 * swallow a real press. That reasoning survives; what does not is the message. Once
                 * a stepped control works out its value first, there is no direction left on the
                 * wire — so the only thing an end-stop press could carry is DASH's *current* value,
                 * and sending that would command the module to whatever DASH last believed. A stale
                 * belief silently swallowing a press is a nuisance; a stale belief silently issuing
                 * a command is the second-source-of-truth failure this whole design exists to
                 * avoid. Between the two, the nuisance wins.
                 *
                 * And the nuisance is small: a module re-states everything on its heartbeat, so a
                 * stale belief corrects itself within a second or two without anyone pressing
                 * anything.
                 */
                if (landed.isNullOrEmpty()) {
                    if (binding.step != 0) {
                        Log.i(
                            Optimism.TAG,
                            "`${binding.control}` has nowhere to step from " +
                                "'${values[binding.control]}' — end stop, or a value its board does " +
                                "not list. Nothing sent.",
                        )
                    } else {
                        // A genuinely momentary control: no value, nothing to predict, nothing to
                        // revert. It still goes out — that is the whole of what it does.
                        actions.sendAction(moduleId, binding.control, null)
                    }
                } else {
                    actions.sendAction(moduleId, binding.control, landed)
                    optimism.predict(binding.control, landed, System.currentTimeMillis())
                }
            }
        },
    )
}
