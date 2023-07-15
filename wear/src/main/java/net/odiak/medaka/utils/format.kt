package net.odiak.medaka.utils

fun Int.signed(): String {
    return if (this > 0) "+$this" else "$this"
}