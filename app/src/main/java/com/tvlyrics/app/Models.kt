package com.tvlyrics.app

data class Track(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
    val albumArtUrl: String? = null,
)

data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class Line(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word>?,
    val translation: String? = null,
)

data class Lyrics(
    val lines: List<Line>,
    val source: String,
)
