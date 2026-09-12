@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

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
import org.jetbrains.skiko.win32Log

/**
 * Windows host for real Compose UI ([CanvasLayersComposeScene] + [FrameRecomposer]).
 */
class WindowsComposeApplication(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
) {
    val window: WindowsComposeWindow = WindowsComposeWindow(title, width, height)
    private val inputState = WindowsInputState()
    private val archComponentsOwner = DefaultArchitectureComponentsOwner(enforceMainThread = false)
    private val textInputService = WindowsTextInputService()
    private val platformContext = WindowsPlatformContext(archComponentsOwner, textInputService)

    private val frameRecomposer = FrameRecomposer(SkikoDispatchers.Main) {
        window.layer.needRender()
    }
    private val sceneRenderingScope = SingleComposeSceneRenderingScope {
        window.layer.needRender()
    }

    private val scene = CanvasLayersComposeScene(
        frameRecomposer = frameRecomposer,
        platformContext = platformContext,
        invalidateLayout = sceneRenderingScope::onSceneInvalidation,
        invalidateDraw = sceneRenderingScope::onSceneInvalidation,
    )

    /**
     * Run the application with the given Compose content.
     */
    fun run(content: @Composable () -> Unit) {
        scene.density = Density(window.layer.contentScale)
        scene.setContent {
            WindowsWindowChrome(title = title, window = window, content = content)
        }
        archComponentsOwner.enableSavedStateHandles()
        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        window.layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                val sizeInPx = IntSize(width, height)
                scene.size = sizeInPx
                with(sceneRenderingScope) {
                    scene.render(frameRecomposer, canvas.asComposeCanvas(), nanoTime)
                }
            }
        }

        win32Log("app: scene + content ready, starting window loop")
        try {
            window.run(onEvent = ::handleEvent)
        } catch (t: Throwable) {
            win32Log("app: EXCEPTION from window.run -> ${t::class.simpleName}: ${t.message}")
            throw t
        }
        win32Log("app: window loop finished normally")

        archComponentsOwner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        archComponentsOwner.viewModelStore.clear()
        scene.close()
        frameRecomposer.close()
    }

    /**
     * Handle Windows events.
     */
    private fun handleEvent(event: WindowsEvent) {
        when (event) {
            is WindowsEvent.KeyEvent -> {
                scene.dispatchWindowsKeyEvent(event, inputState)
            }
            is WindowsEvent.MouseMoveEvent -> {
                inputState.updateModifiersFromMouse(0) // TODO: Pass actual modifier state
                scene.dispatchWindowsMouseEvent(event, inputState)
            }
            is WindowsEvent.MouseButtonEvent -> {
                inputState.updateModifiersFromMouse(0) // TODO: Pass actual modifier state
                scene.dispatchWindowsMouseButtonEvent(event, inputState)
            }
            is WindowsEvent.MouseWheelEvent -> {
                scene.dispatchWindowsMouseWheelEvent(event, inputState)
            }
            is WindowsEvent.ResizeEvent -> {
                scene.density = Density(window.layer.contentScale)
                window.layer.needRender()
            }
            is WindowsEvent.CloseEvent -> {
                // Window close handled by message loop
            }
            is WindowsEvent.FocusEvent -> {
                // Focus change handled
            }
            is WindowsEvent.PaintEvent -> {
                window.layer.renderImmediately()
            }
            is WindowsEvent.MoveEvent -> {
                // Move event handled
            }
        }
    }
}
