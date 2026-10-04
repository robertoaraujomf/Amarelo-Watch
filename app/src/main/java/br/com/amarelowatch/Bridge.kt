package br.com.amarelowatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

object Bridge {

    data class State(
        val streaming: Boolean = false,
        val starting: Boolean = false,
        val port: Int = Settings.DEFAULT_PORT,
        val url: String? = null,
        val clients: Int = 0,
        val fps: Int = 0,
        val kbps: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val mode: String = "",
        val letterboxed: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val observers = CopyOnWriteArrayList<(State) -> Unit>()

    fun observe(observer: (State) -> Unit) {
        observers.add(observer)
        observer(_state.value)
    }

    fun unobserve(observer: (State) -> Unit) {
        observers.remove(observer)
    }

    fun update(block: (State) -> State) {
        val next = block(_state.value)
        _state.value = next
        observers.forEach { runCatching { it(next) } }
    }

    fun current(): State = _state.value

    fun log(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$stamp  $message"
        android.util.Log.i("AmareloWatch", message)
        _logs.value = (_logs.value + line).takeLast(60)
    }

    fun reset() {
        _state.value = State()
        _logs.value = emptyList()
    }
}
