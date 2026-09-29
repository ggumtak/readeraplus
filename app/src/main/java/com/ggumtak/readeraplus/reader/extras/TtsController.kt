package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.reader.ReaderHost

/**
 * CONTRACT STUB — text-to-speech: reads from the current page, highlights the sentence, turns pages,
 * shows its own control bar over the reader.
 */
class TtsController(private val host: ReaderHost) {
    val isSpeaking: Boolean get() = TODO("reader-extras")
    fun start(): Unit = TODO("reader-extras")
    fun pause(): Unit = TODO("reader-extras")
    fun stop(): Unit = TODO("reader-extras")
    /** Host calls this when the user turned pages manually while TTS is active. */
    fun onUserNavigated(): Unit = TODO("reader-extras")
    fun release(): Unit = TODO("reader-extras")
}
