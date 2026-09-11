@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.linux

import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.SingleComposeSceneRenderingScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.enableSavedStateHandles
import org.jetbrains.skiko.SkikoDispatchers
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.WaylandEvent
import org.jetbrains.skiko.WaylandEventType
import org.jetbrains.skiko.WaylandWindow

/**
 * Wayland host for real Compose UI ([CanvasLayersComposeScene] + [FrameRecomposer]).
 */
class LinuxComposeApplication(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
) {
    val window: WaylandWindow get() = composeWindow.window
    private val composeWindow = LinuxComposeWindow(title, width, height)
    private val inputState = WaylandInputState()
    private val archComponentsOwner = DefaultArchitectureComponentsOwner(enforceMainThread = false)
    private val textInputService = LinuxTextInputService(
        window = composeWindow.window,
        contentScale = { composeWindow.layer.contentScale },
    )
    private val backNavigationInput = LinuxBackNavigationInput()
    private val platformContext = LinuxPlatformContext(archComponentsOwner, textInputService)

    private val frameRecomposer = FrameRecomposer(SkikoDispatchers.Main) {
        composeWindow.layer.needRender()
    }
    private val sceneRenderingScope = SingleComposeSceneRenderingScope {
        composeWindow.layer.needRender()
    }

    private val scene = CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        platformContext = platformContext,
        invalidateLayout = sceneRenderingScope::onSceneInvalidation,
        invalidateDraw = sceneRenderingScope::onSceneInvalidation,
    )

    fun run(content: @Composable () -> Unit) {
        scene.density = Density(composeWindow.layer.contentScale)
        scene.setContent {
            LinuxWindowChrome(title = title, window = composeWindow.window, content = content)
        }
        archComponentsOwner.enableSavedStateHandles()
        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        archComponentsOwner.navigationEventDispatcher.addInput(backNavigationInput)

        composeWindow.layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                val sizeInPx = IntSize(width, height)
                scene.size = sizeInPx
                with(sceneRenderingScope) {
                    scene.render(frameRecomposer, canvas.asComposeCanvas(), nanoTime)
                }
            }
        }

        composeWindow.window.onImeEvent = { imeEvent -> textInputService.handleImeEvent(imeEvent) }
        composeWindow.run(onEvent = ::handleEvent)

        archComponentsOwner.navigationEventDispatcher.removeInput(backNavigationInput)
        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        archComponentsOwner.viewModelStore.clear()
        scene.close()
        frameRecomposer.close()
    }

    private fun handleEvent(event: WaylandEvent) {
        if (event.type == WaylandEventType.Scale) {
            scene.density = Density(event.scale)
            return
        }
        if (event.type == WaylandEventType.Key) {
            println("composekn: key keyCode=${event.keyCode} keysym=${event.keysym} pressed=${event.pressed}")
            scene.dispatchWaylandKeyEvent(event, inputState, backNavigationInput)
            return
        }
        scene.dispatchWaylandEvent(event, composeWindow.layer.contentScale, inputState)
    }
}
