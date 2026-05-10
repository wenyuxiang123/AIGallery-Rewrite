package com.aigallery.rewrite.tool

import java.util.concurrent.atomic.AtomicInteger

class CircuitBreaker(
    val toolName: String,
    private val failureThreshold: Int = 5,
    private val recoveryTimeoutMs: Long = 60_000
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }
    
    private val _state = AtomicInteger(State.CLOSED.ordinal)
    val state: State get() = State.values()[_state.get()]
    val isOpen: Boolean get() = state == State.OPEN
    
    private var failureCount = AtomicInteger(0)
    private var lastFailureTime = 0L
    
    fun recordSuccess() {
        failureCount.set(0)
        _state.set(State.CLOSED.ordinal)
    }
    
    fun recordFailure() {
        failureCount.incrementAndGet()
        lastFailureTime = System.currentTimeMillis()
        if (failureCount.get() >= failureThreshold) {
            _state.set(State.OPEN.ordinal)
        }
    }
    
    fun tryPass(): Boolean {
        return when (state) {
            State.CLOSED -> true
            State.HALF_OPEN -> true
            State.OPEN -> {
                if (System.currentTimeMillis() - lastFailureTime > recoveryTimeoutMs) {
                    _state.set(State.HALF_OPEN.ordinal)
                    true
                } else false
            }
        }
    }
}
