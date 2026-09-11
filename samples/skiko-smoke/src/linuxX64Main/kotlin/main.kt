package main

import org.jetbrains.skiko.WaylandWindow

fun main() {
    val window = WaylandWindow("skiko-smoke", 640, 480)
    while (window.poll()) {
        // pump events only
    }
    window.destroy()
}
