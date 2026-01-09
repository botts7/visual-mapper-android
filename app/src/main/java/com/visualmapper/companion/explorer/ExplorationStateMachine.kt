package com.visualmapper.companion.explorer

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State machine for exploration lifecycle.
 * Extracted from AppExplorerService for testability, clarity, and modularity.
 *
 * Phase 1 Refactor: This replaces the implicit state management spread across
 * AppExplorerService with an explicit, testable state machine.
 *
 * States:
 * - IDLE: Not exploring, service may or may not be running
 * - INITIALIZING: Setting up exploration (launching app, enabling high-precision mode)
 * - EXPLORING: Actively tapping elements and discovering screens
 * - PAUSED: User paused exploration
 * - STUCK: No progress detected, recovery strategies being attempted
 * - COMPLETING: Wrapping up exploration (generating results)
 * - COMPLETED: Exploration finished, results available
 *
 * Thread Safety: All state transitions go through processEvent() which updates
 * the StateFlow atomically.
 */
class ExplorationStateMachine {

    companion object {
        private const val TAG = "ExplorationSM"

        // Thresholds for state transitions
        const val STUCK_THRESHOLD = 5           // Consecutive no-progress events before STUCK
        const val STUCK_RECOVERY_THRESHOLD = 3  // Recovery attempts before giving up
    }

    /**
     * Exploration states
     */
    enum class State {
        IDLE,           // Not exploring
        INITIALIZING,   // Setting up exploration
        EXPLORING,      // Actively exploring
        PAUSED,         // User paused
        STUCK,          // Recovery needed
        COMPLETING,     // Finishing exploration
        COMPLETED       // Done
    }

    /**
     * Events that trigger state transitions
     */
    enum class Event {
        // Lifecycle events
        START_REQUESTED,
        INITIALIZATION_COMPLETE,
        STOP_REQUESTED,

        // Progress events
        ELEMENT_TAPPED,
        NEW_SCREEN_DISCOVERED,
        NEW_ELEMENTS_FOUND,
        NO_PROGRESS_DETECTED,

        // Stuck/Recovery events
        STUCK_THRESHOLD_REACHED,
        RECOVERY_SUCCEEDED,
        RECOVERY_FAILED,

        // User interaction events
        PAUSE_REQUESTED,
        RESUME_REQUESTED,
        USER_HELPED,

        // Completion events
        MAX_ITERATIONS_REACHED,
        COVERAGE_THRESHOLD_REACHED,
        QUEUE_EXHAUSTED
    }

    // Observable state
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    // Counters for state transition logic
    private var consecutiveNoProgressCount = 0
    private var recoveryAttempts = 0
    private var totalElementsTapped = 0
    private var totalScreensDiscovered = 0

    // Callback for state change notifications
    private var onStateChanged: ((State, State, Event) -> Unit)? = null

    /**
     * Set a callback for state changes.
     * Called with (oldState, newState, triggeringEvent)
     */
    fun setOnStateChangedListener(listener: (State, State, Event) -> Unit) {
        onStateChanged = listener
    }

    /**
     * Process an event and transition to the appropriate state.
     * Returns the new state.
     *
     * Thread-safe: StateFlow updates are atomic.
     */
    fun processEvent(event: Event): State {
        val currentState = _state.value
        val newState = when (currentState) {
            State.IDLE -> handleIdleState(event)
            State.INITIALIZING -> handleInitializingState(event)
            State.EXPLORING -> handleExploringState(event)
            State.PAUSED -> handlePausedState(event)
            State.STUCK -> handleStuckState(event)
            State.COMPLETING -> handleCompletingState(event)
            State.COMPLETED -> handleCompletedState(event)
        }

        if (newState != currentState) {
            Log.i(TAG, "State transition: $currentState -> $newState (event: $event)")
            _state.value = newState
            onStateChanged?.invoke(currentState, newState, event)
        }

        return newState
    }

    // =========================================================================
    // State Handlers
    // =========================================================================

    private fun handleIdleState(event: Event): State {
        return when (event) {
            Event.START_REQUESTED -> {
                resetCounters()
                State.INITIALIZING
            }
            else -> State.IDLE
        }
    }

    private fun handleInitializingState(event: Event): State {
        return when (event) {
            Event.INITIALIZATION_COMPLETE -> State.EXPLORING
            Event.STOP_REQUESTED -> State.IDLE
            else -> State.INITIALIZING
        }
    }

    private fun handleExploringState(event: Event): State {
        return when (event) {
            Event.NEW_SCREEN_DISCOVERED -> {
                consecutiveNoProgressCount = 0
                totalScreensDiscovered++
                State.EXPLORING
            }
            Event.NEW_ELEMENTS_FOUND -> {
                consecutiveNoProgressCount = 0
                State.EXPLORING
            }
            Event.ELEMENT_TAPPED -> {
                totalElementsTapped++
                State.EXPLORING
            }
            Event.NO_PROGRESS_DETECTED -> {
                consecutiveNoProgressCount++
                Log.d(TAG, "No progress: $consecutiveNoProgressCount / $STUCK_THRESHOLD")
                if (consecutiveNoProgressCount >= STUCK_THRESHOLD) {
                    recoveryAttempts = 0 // Reset for new stuck episode
                    State.STUCK
                } else {
                    State.EXPLORING
                }
            }
            Event.PAUSE_REQUESTED -> State.PAUSED
            Event.STOP_REQUESTED -> State.COMPLETING
            Event.MAX_ITERATIONS_REACHED -> State.COMPLETING
            Event.COVERAGE_THRESHOLD_REACHED -> State.COMPLETING
            Event.QUEUE_EXHAUSTED -> State.COMPLETING
            else -> State.EXPLORING
        }
    }

    private fun handlePausedState(event: Event): State {
        return when (event) {
            Event.RESUME_REQUESTED -> State.EXPLORING
            Event.STOP_REQUESTED -> State.COMPLETING
            else -> State.PAUSED
        }
    }

    private fun handleStuckState(event: Event): State {
        return when (event) {
            Event.RECOVERY_SUCCEEDED -> {
                Log.i(TAG, "Recovery succeeded after $recoveryAttempts attempts")
                consecutiveNoProgressCount = 0
                State.EXPLORING
            }
            Event.USER_HELPED -> {
                Log.i(TAG, "User helped navigate out of stuck state")
                consecutiveNoProgressCount = 0
                State.EXPLORING
            }
            Event.RECOVERY_FAILED -> {
                recoveryAttempts++
                Log.w(TAG, "Recovery failed: $recoveryAttempts / $STUCK_RECOVERY_THRESHOLD")
                if (recoveryAttempts >= STUCK_RECOVERY_THRESHOLD) {
                    State.COMPLETING
                } else {
                    State.STUCK // Stay stuck, try another recovery strategy
                }
            }
            Event.STOP_REQUESTED -> State.COMPLETING
            Event.NEW_SCREEN_DISCOVERED -> {
                // External navigation (user or recovery) succeeded
                consecutiveNoProgressCount = 0
                totalScreensDiscovered++
                State.EXPLORING
            }
            else -> State.STUCK
        }
    }

    private fun handleCompletingState(event: Event): State {
        // COMPLETING always transitions to COMPLETED
        return State.COMPLETED
    }

    private fun handleCompletedState(event: Event): State {
        return when (event) {
            Event.START_REQUESTED -> {
                resetCounters()
                State.INITIALIZING
            }
            else -> State.COMPLETED
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private fun resetCounters() {
        consecutiveNoProgressCount = 0
        recoveryAttempts = 0
        totalElementsTapped = 0
        totalScreensDiscovered = 0
    }

    /**
     * Reset state machine to IDLE (for service restart)
     */
    fun reset() {
        Log.i(TAG, "Resetting state machine to IDLE")
        resetCounters()
        _state.value = State.IDLE
    }

    /**
     * Check if exploration is currently active (in a running state)
     */
    fun isActive(): Boolean = _state.value in listOf(
        State.INITIALIZING, State.EXPLORING, State.STUCK
    )

    /**
     * Check if exploration can be started (only from IDLE or COMPLETED)
     */
    fun canStart(): Boolean = _state.value in listOf(State.IDLE, State.COMPLETED)

    /**
     * Get current statistics for progress reporting
     */
    fun getStatistics(): Statistics {
        return Statistics(
            currentState = _state.value,
            consecutiveNoProgress = consecutiveNoProgressCount,
            recoveryAttempts = recoveryAttempts,
            totalElementsTapped = totalElementsTapped,
            totalScreensDiscovered = totalScreensDiscovered
        )
    }

    /**
     * Statistics for progress reporting
     */
    data class Statistics(
        val currentState: State,
        val consecutiveNoProgress: Int,
        val recoveryAttempts: Int,
        val totalElementsTapped: Int,
        val totalScreensDiscovered: Int
    )
}
