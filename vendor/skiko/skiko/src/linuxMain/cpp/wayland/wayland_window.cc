#include "wayland_bridge.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <wayland-client.h>
#include <wayland-egl.h>

#include <sys/mman.h>
#include <sys/stat.h>
#include <poll.h>
#include <errno.h>
#include <fcntl.h>
#include <time.h>
#include <unistd.h>

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <string>
#include <utility>
#include <vector>

#if defined(__linux__)
#include <sys/syscall.h>
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef SYS_memfd_create
#if defined(__x86_64__)
#define SYS_memfd_create 319
#elif defined(__aarch64__)
#define SYS_memfd_create 279
#endif
#endif
#endif

#include <xkbcommon/xkbcommon.h>

#include "xdg-shell-client-protocol.h"
#include "fractional-scale-v1-client-protocol.h"
#include "xdg-decoration-unstable-v1-client-protocol.h"
#include "text-input-unstable-v3-client-protocol.h"

#undef WL_PRIVATE
#define WL_PRIVATE
#include "xdg-shell-protocol.c"
#include "fractional-scale-v1-protocol.c"
#include "xdg-decoration-unstable-v1-protocol.c"
#include "text-input-unstable-v3-protocol.c"

static constexpr int kMaxEvents = 256;

static constexpr uint32_t kWaylandToEvdevOffset = 8;
static constexpr uint32_t kWlModShift = 0x1;
static constexpr uint32_t kWlModCaps = 0x2;
static constexpr uint32_t kWlModCtrl = 0x4;
static constexpr uint32_t kWlModAlt = 0x8;
static constexpr uint32_t kWlModMod4 = 0x40;

/* String-carrying IME event (zwp_text_input_v3), delivered to Kotlin via
 * composekn_window_pop_ime_event. Kept separate from ComposeKNEvent because the
 * fixed-size C struct cannot hold a variable-length UTF-8 string. */
struct ComposeKNImeEvent {
    int32_t kind = 0;       /* ComposeKNImeKind */
    std::string text;       /* PREEDIT/COMMIT payload (UTF-8) */
    uint32_t a = 0;         /* PREEDIT: cursor_begin; DELETE: before_length */
    uint32_t b = 0;         /* PREEDIT: cursor_end;   DELETE: after_length  */
};

struct ComposeKNWindow {
    wl_display* display = nullptr;
    wl_registry* registry = nullptr;
    wl_compositor* compositor = nullptr;
    xdg_wm_base* wm_base = nullptr;
    wl_seat* seat = nullptr;
    wl_pointer* pointer = nullptr;
    wl_keyboard* keyboard = nullptr;
    wl_touch* touch = nullptr;
    wl_surface* surface = nullptr;
    xdg_surface* shell_surface = nullptr;
    xdg_toplevel* toplevel = nullptr;
    zxdg_decoration_manager_v1* decoration_manager = nullptr;
    zxdg_toplevel_decoration_v1* toplevel_decoration = nullptr;
    uint32_t decoration_mode = ZXDG_TOPLEVEL_DECORATION_V1_MODE_CLIENT_SIDE;
    wl_egl_window* egl_window = nullptr;
    wp_fractional_scale_manager_v1* fractional_scale_manager = nullptr;
    wp_fractional_scale_v1* fractional_scale = nullptr;

    EGLDisplay egl_display = EGL_NO_DISPLAY;
    EGLSurface egl_surface = EGL_NO_SURFACE;
    EGLContext egl_context = EGL_NO_CONTEXT;
    EGLConfig egl_config = nullptr;

    int width = 0;
    int height = 0;
    float scale = 1.0f;
    int buffer_scale = 1;
    bool configured = false;
    bool egl_ready = false;
    /** When true, skip EGL init so Vulkan can own the wl_surface. */
    bool prefer_vulkan = false;
    bool close_requested = false;
    /** True until Kotlin [consume_close_requested] fires onCloseRequest once (DO_NOTHING). */
    bool close_event_pending = false;
    bool resized = false;
    bool maximized = false;
    bool fullscreen = false;
    /** App requested non-resizable：configure 后不要清掉 min=max。 */
    bool size_locked = false;
    uint32_t configure_serial = 0;
    /** True after request_size：下一次 toplevel configure 后清掉 min/max 约束。 */
    bool clear_size_constraints_after_configure = false;

    bool pointer_inside = false;
    double pointer_x = 0.0;
    double pointer_y = 0.0;
    uint32_t pointer_buttons = 0;
    uint32_t modifiers = 0;

    // Active touch points: id -> (x, y) in surface-local (unscaled) coordinates.
    std::map<uint32_t, std::pair<double, double>> touch_points;

    wl_callback* frame_callback = nullptr;
    bool frame_pending = false;
    bool frame_requested = false;
    /** CLOCK_MONOTONIC ns when frame_callback was armed; 0 if idle. */
    int64_t frame_armed_ns = 0;
    bool in_dispatch = false;

    std::deque<ComposeKNEvent> events;

    wl_data_device_manager* data_device_manager = nullptr;
    wl_data_device* data_device = nullptr;
    wl_shm* shm = nullptr;
    wl_data_source* clipboard_source = nullptr;
    wl_data_offer* selection_offer = nullptr;

    // selection receive caches (get*)
    std::string clipboard_cache;
    std::string clipboard_html_cache;
    std::string clipboard_rtf_cache;
    std::string clipboard_files_cache;
    std::vector<uint8_t> clipboard_image_bgra;
    int32_t clipboard_image_w = 0;
    int32_t clipboard_image_h = 0;
    bool selection_has_text = false;
    bool selection_has_html = false;
    bool selection_has_rtf = false;
    bool selection_has_image = false;
    bool selection_has_uri = false;
    std::vector<std::string> selection_mimes;
    bool selection_read_pending = false;

    // set* pending for data_source send
    std::string clipboard_set_pending;
    std::string clipboard_set_html;
    std::string clipboard_set_rtf;
    std::string clipboard_set_files_uri;
    std::vector<uint8_t> clipboard_set_bmp;
    int32_t clipboard_set_image_w = 0;
    int32_t clipboard_set_image_h = 0;
    std::vector<uint8_t> clipboard_set_bgra;

    // DnD outbound
    wl_data_source* drag_source = nullptr;
    std::string drag_set_text;
    std::string drag_set_files_uri;
    std::vector<uint8_t> drag_icon_bgra;
    int32_t drag_icon_w = 0;
    int32_t drag_icon_h = 0;
    int32_t drag_hot_x = 0;
    int32_t drag_hot_y = 0;
    wl_surface* drag_icon_surface = nullptr;
    wl_buffer* drag_icon_buffer = nullptr;
    void* drag_icon_shm = nullptr;
    size_t drag_icon_shm_size = 0;
    int drag_icon_fd = -1;
    int32_t drag_result = -1; // -1 pending/none, 0 cancel, 1 success
    bool drag_active = false;
    bool drag_drop_performed = false;

    // DnD inbound
    wl_data_offer* dnd_offer = nullptr;
    uint32_t dnd_serial = 0;
    double dnd_x = 0.0;
    double dnd_y = 0.0;
    bool dnd_has_text = false;
    bool dnd_has_uri = false;
    std::string dnd_files_cache;
    std::string dnd_text_cache;
    bool dnd_accept = false;

    uint32_t last_serial = 0;

    zwp_text_input_manager_v3* text_input_manager = nullptr;
    zwp_text_input_v3* text_input = nullptr;
    bool text_input_enabled = false;
    std::deque<ComposeKNImeEvent> ime_events;

    xkb_context* xkb_ctx = nullptr;
    xkb_keymap* keymap = nullptr;
    xkb_state* kb_state = nullptr;
};

static ComposeKNWindow* g_primary_window = nullptr;
// 不用 unordered_set：会拉 std::__throw_bad_array_new_length，konan ld.lld 链不上。
static constexpr size_t kMaxLiveWindows = 64;
static ComposeKNWindow* g_live_windows[kMaxLiveWindows] = {};
static size_t g_live_window_count = 0;

/** Process-wide Wayland/EGL connection shared by all ComposeKNWindow instances. */
struct ComposeKNSharedDisplay {
    wl_display* display = nullptr;
    wl_registry* registry = nullptr;
    wl_compositor* compositor = nullptr;
    xdg_wm_base* wm_base = nullptr;
    wl_seat* seat = nullptr;
    wl_pointer* pointer = nullptr;
    wl_keyboard* keyboard = nullptr;
    wl_touch* touch = nullptr;
    wl_data_device_manager* data_device_manager = nullptr;
    wl_data_device* data_device = nullptr;
    wl_shm* shm = nullptr;
    zxdg_decoration_manager_v1* decoration_manager = nullptr;
    zwp_text_input_manager_v3* text_input_manager = nullptr;
    zwp_text_input_v3* text_input = nullptr;
    wp_fractional_scale_manager_v1* fractional_scale_manager = nullptr;
    EGLDisplay egl_display = EGL_NO_DISPLAY;
    EGLConfig egl_config = nullptr;
    /** 单线程多窗共用一个 EGLContext（多 DirectContext 抢同一 display 会在 llvmpipe 崩）。 */
    EGLContext egl_context = EGL_NO_CONTEXT;
    bool egl_initialized = false;
    int egl_context_client_version = 2;
    int refcount = 0;
    ComposeKNWindow* pointer_focus = nullptr;
    ComposeKNWindow* keyboard_focus = nullptr;
};

static ComposeKNSharedDisplay g_shared;
/** True after the first window this host-loop cycle has done prepare_read/read_events. */
static bool g_display_read_this_cycle = false;

struct SurfaceMapEntry {
    wl_surface* surface = nullptr;
    ComposeKNWindow* window = nullptr;
};
static SurfaceMapEntry g_surface_map[kMaxLiveWindows] = {};
static size_t g_surface_map_count = 0;

struct TouchRouteEntry {
    int32_t id = -1;
    ComposeKNWindow* window = nullptr;
};
static constexpr size_t kMaxTouchRoutes = 16;
static TouchRouteEntry g_touch_routes[kMaxTouchRoutes] = {};

static bool live_window_contains(ComposeKNWindow* window) {
    for (size_t i = 0; i < g_live_window_count; ++i) {
        if (g_live_windows[i] == window) {
            return true;
        }
    }
    return false;
}

static void live_window_insert(ComposeKNWindow* window) {
    if (window == nullptr || live_window_contains(window)) {
        return;
    }
    if (g_live_window_count >= kMaxLiveWindows) {
        std::fprintf(stderr, "composekn: live window table full (%zu)\n", kMaxLiveWindows);
        return;
    }
    g_live_windows[g_live_window_count++] = window;
}

static void live_window_erase(ComposeKNWindow* window) {
    for (size_t i = 0; i < g_live_window_count; ++i) {
        if (g_live_windows[i] == window) {
            g_live_windows[i] = g_live_windows[g_live_window_count - 1];
            g_live_windows[g_live_window_count - 1] = nullptr;
            --g_live_window_count;
            return;
        }
    }
}

static ComposeKNWindow* surface_map_lookup(wl_surface* surface) {
    if (surface == nullptr) {
        return nullptr;
    }
    for (size_t i = 0; i < g_surface_map_count; ++i) {
        if (g_surface_map[i].surface == surface) {
            return g_surface_map[i].window;
        }
    }
    return nullptr;
}

static void surface_map_insert(wl_surface* surface, ComposeKNWindow* window) {
    if (surface == nullptr || window == nullptr) {
        return;
    }
    for (size_t i = 0; i < g_surface_map_count; ++i) {
        if (g_surface_map[i].surface == surface) {
            g_surface_map[i].window = window;
            return;
        }
    }
    if (g_surface_map_count >= kMaxLiveWindows) {
        std::fprintf(stderr, "composekn: surface map full (%zu)\n", kMaxLiveWindows);
        return;
    }
    g_surface_map[g_surface_map_count].surface = surface;
    g_surface_map[g_surface_map_count].window = window;
    ++g_surface_map_count;
}

static void surface_map_erase(ComposeKNWindow* window) {
    if (window == nullptr) {
        return;
    }
    for (size_t i = 0; i < g_surface_map_count; ) {
        if (g_surface_map[i].window == window) {
            g_surface_map[i] = g_surface_map[g_surface_map_count - 1];
            g_surface_map[g_surface_map_count - 1] = {};
            --g_surface_map_count;
        } else {
            ++i;
        }
    }
}

static void touch_route_set(int32_t id, ComposeKNWindow* window) {
    for (size_t i = 0; i < kMaxTouchRoutes; ++i) {
        if (g_touch_routes[i].id == id) {
            g_touch_routes[i].window = window;
            return;
        }
    }
    for (size_t i = 0; i < kMaxTouchRoutes; ++i) {
        if (g_touch_routes[i].id < 0 || g_touch_routes[i].window == nullptr) {
            g_touch_routes[i].id = id;
            g_touch_routes[i].window = window;
            return;
        }
    }
}

static ComposeKNWindow* touch_route_lookup(int32_t id) {
    for (size_t i = 0; i < kMaxTouchRoutes; ++i) {
        if (g_touch_routes[i].id == id) {
            return g_touch_routes[i].window;
        }
    }
    return nullptr;
}

static void touch_route_clear(int32_t id) {
    for (size_t i = 0; i < kMaxTouchRoutes; ++i) {
        if (g_touch_routes[i].id == id) {
            g_touch_routes[i] = {};
            g_touch_routes[i].id = -1;
            return;
        }
    }
}

static void touch_route_clear_window(ComposeKNWindow* window) {
    if (window == nullptr) {
        return;
    }
    for (size_t i = 0; i < kMaxTouchRoutes; ++i) {
        if (g_touch_routes[i].window == window) {
            g_touch_routes[i] = {};
            g_touch_routes[i].id = -1;
        }
    }
}

static void touch_route_clear_all() {
    for (size_t i = 0; i < kMaxTouchRoutes; ++i) {
        g_touch_routes[i] = {};
        g_touch_routes[i].id = -1;
    }
}

/** Prefer the caller's window pointer; fall back to primary (clipboard / legacy single-window). */
static ComposeKNWindow* resolve_window(ComposeKNWindow* window) {
    ComposeKNWindow* candidate = window != nullptr ? window : g_primary_window;
    if (candidate == nullptr) {
        return nullptr;
    }
    // destroy 后 Kotlin 仍可能持有野指针（关窗与 renderImmediately 重入）；拒绝已释放窗口。
    if (!live_window_contains(candidate)) {
        return nullptr;
    }
    return candidate;
}

/** Desktop DO_NOTHING_ON_CLOSE: mark close + pending notify; do not tear down the surface. */
static void mark_close_requested(ComposeKNWindow* window) {
    if (window == nullptr) {
        return;
    }
    window->close_requested = true;
    window->close_event_pending = true;
}

static void ensure_data_device();
static void read_selection_into_cache(ComposeKNWindow* window);
static void composekn_process_deferred_selection(ComposeKNWindow* window);
static bool init_egl(ComposeKNWindow* window);
static void try_init_egl_if_needed(ComposeKNWindow* window);
static void composekn_flush_deferred_frame(ComposeKNWindow* window);
static void ensure_text_input();
static void alias_shared_onto_window(ComposeKNWindow* window);
static void refresh_all_window_shared_aliases();
static bool acquire_shared_display();
static void release_shared_display();
static ComposeKNWindow* clipboard_target_window();
static void synth_left_button_up_if_pressed(ComposeKNWindow* window, const char* reason);
static void cleanup_drag_icon(ComposeKNWindow* window);
static void finish_outbound_drag(ComposeKNWindow* window, int32_t result);

static void alias_shared_onto_window(ComposeKNWindow* window) {
    if (window == nullptr) {
        return;
    }
    window->display = g_shared.display;
    window->registry = g_shared.registry;
    window->compositor = g_shared.compositor;
    window->wm_base = g_shared.wm_base;
    window->seat = g_shared.seat;
    window->pointer = g_shared.pointer;
    window->keyboard = g_shared.keyboard;
    window->touch = g_shared.touch;
    window->data_device_manager = g_shared.data_device_manager;
    window->data_device = g_shared.data_device;
    window->shm = g_shared.shm;
    window->decoration_manager = g_shared.decoration_manager;
    window->text_input_manager = g_shared.text_input_manager;
    window->text_input = g_shared.text_input;
    window->fractional_scale_manager = g_shared.fractional_scale_manager;
    if (g_shared.egl_initialized) {
        window->egl_display = g_shared.egl_display;
        window->egl_config = g_shared.egl_config;
        window->egl_context = g_shared.egl_context;
    }
}

static void refresh_all_window_shared_aliases() {
    for (size_t i = 0; i < g_live_window_count; ++i) {
        alias_shared_onto_window(g_live_windows[i]);
    }
}

static void clear_window_shared_aliases(ComposeKNWindow* window) {
    if (window == nullptr) {
        return;
    }
    window->display = nullptr;
    window->registry = nullptr;
    window->compositor = nullptr;
    window->wm_base = nullptr;
    window->seat = nullptr;
    window->pointer = nullptr;
    window->keyboard = nullptr;
    window->touch = nullptr;
    window->data_device_manager = nullptr;
    window->data_device = nullptr;
    window->shm = nullptr;
    window->decoration_manager = nullptr;
    window->text_input_manager = nullptr;
    window->text_input = nullptr;
    window->fractional_scale_manager = nullptr;
    window->egl_display = EGL_NO_DISPLAY;
    window->egl_config = nullptr;
    window->egl_context = EGL_NO_CONTEXT;
}

static ComposeKNWindow* clipboard_target_window() {
    if (g_primary_window != nullptr && live_window_contains(g_primary_window)) {
        return g_primary_window;
    }
    if (g_live_window_count > 0) {
        return g_live_windows[0];
    }
    return nullptr;
}

static void push_event(ComposeKNWindow* window, const ComposeKNEvent& event) {
    if (window->events.size() >= kMaxEvents) {
        window->events.pop_front();
    }
    window->events.push_back(event);
}

static void push_ime_event(ComposeKNWindow* window, const ComposeKNImeEvent& event) {
    if (window->ime_events.size() >= kMaxEvents) {
        window->ime_events.pop_front();
    }
    window->ime_events.push_back(event);
}

static int sanitize_dimension(int value) {
    return (value > 0 && value <= 16384) ? value : 0;
}

static void push_scale_event(ComposeKNWindow* window) {
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_SCALE;
    event.scale = window->scale;
    push_event(window, event);
}

static int physical_width(const ComposeKNWindow* window) {
    return sanitize_dimension(window->width);
}

static int physical_height(const ComposeKNWindow* window) {
    return sanitize_dimension(window->height);
}

static void resize_egl_window(ComposeKNWindow* window) {
    if (window->egl_window == nullptr) {
        return;
    }
    const int w = physical_width(window);
    const int h = physical_height(window);
    if (w > 0 && h > 0) {
        wl_egl_window_resize(window->egl_window, w, h, 0, 0);
        window->resized = true;
    }
}

static void update_buffer_scale(ComposeKNWindow* window) {
    // Keep buffer_scale at 1 until fractional-scale support is re-enabled.
    (void)window;
}

static void registry_global(
    void* data,
    wl_registry* registry,
    uint32_t id,
    const char* interface,
    uint32_t version
) {
    auto* shared = static_cast<ComposeKNSharedDisplay*>(data);
    if (shared == nullptr) {
        return;
    }
    if (strcmp(interface, wl_compositor_interface.name) == 0) {
        shared->compositor = static_cast<wl_compositor*>(
            wl_registry_bind(registry, id, &wl_compositor_interface, 4)
        );
    } else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        const uint32_t bind_version = version < 4 ? version : 4;
        shared->wm_base = static_cast<xdg_wm_base*>(
            wl_registry_bind(registry, id, &xdg_wm_base_interface, bind_version)
        );
    } else if (strcmp(interface, wl_seat_interface.name) == 0) {
        const uint32_t bind_version = version < 7 ? version : 7;
        shared->seat = static_cast<wl_seat*>(
            wl_registry_bind(registry, id, &wl_seat_interface, bind_version)
        );
    } else if (strcmp(interface, wp_fractional_scale_manager_v1_interface.name) == 0) {
        // Fractional scale disabled until EGL buffer sizing is fully validated.
        (void)version;
        (void)id;
        (void)registry;
    } else if (strcmp(interface, wl_data_device_manager_interface.name) == 0) {
        shared->data_device_manager = static_cast<wl_data_device_manager*>(
            wl_registry_bind(registry, id, &wl_data_device_manager_interface, 3)
        );
        ensure_data_device();
    } else if (strcmp(interface, wl_shm_interface.name) == 0) {
        shared->shm = static_cast<wl_shm*>(
            wl_registry_bind(registry, id, &wl_shm_interface, 1)
        );
        refresh_all_window_shared_aliases();
    } else if (strcmp(interface, zxdg_decoration_manager_v1_interface.name) == 0) {
        const uint32_t bind_version = version < 2 ? version : 2;
        shared->decoration_manager = static_cast<zxdg_decoration_manager_v1*>(
            wl_registry_bind(registry, id, &zxdg_decoration_manager_v1_interface, bind_version)
        );
    } else if (strcmp(interface, zwp_text_input_manager_v3_interface.name) == 0) {
        const uint32_t bind_version = version < 2 ? version : 2;
        shared->text_input_manager = static_cast<zwp_text_input_manager_v3*>(
            wl_registry_bind(registry, id, &zwp_text_input_manager_v3_interface, bind_version)
        );
    }
}

static void registry_global_remove(void* data, wl_registry* registry, uint32_t id) {
    (void)data;
    (void)registry;
    (void)id;
}

static const wl_registry_listener registry_listener = {
    registry_global,
    registry_global_remove,
};

static void xdg_wm_base_ping(void* data, xdg_wm_base* wm_base, uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(wm_base, serial);
}

static const xdg_wm_base_listener xdg_wm_base_listener = {
    xdg_wm_base_ping,
};

static void xdg_surface_configure(void* data, xdg_surface* shell_surface, uint32_t serial) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    window->configure_serial = serial;
    xdg_surface_ack_configure(shell_surface, serial);
    window->configured = true;
    try_init_egl_if_needed(window);
    composekn_flush_deferred_frame(window);
}

static const xdg_surface_listener xdg_surface_listener = {
    xdg_surface_configure,
};

static void xdg_toplevel_configure(
    void* data,
    xdg_toplevel* toplevel,
    int32_t width,
    int32_t height,
    wl_array* states
) {
    (void)toplevel;
    (void)states;
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (states != nullptr) {
        const uint32_t* state_data = static_cast<const uint32_t*>(states->data);
        const size_t count = states->size / sizeof(uint32_t);
        window->maximized = false;
        window->fullscreen = false;
        for (size_t i = 0; i < count; ++i) {
            if (state_data[i] == XDG_TOPLEVEL_STATE_MAXIMIZED) {
                window->maximized = true;
            }
            if (state_data[i] == XDG_TOPLEVEL_STATE_FULLSCREEN) {
                window->fullscreen = true;
            }
        }
    }
    {
        FILE* f = fopen("/tmp/composekn_debug.log", "a");
        if (f) {
            std::fprintf(f, "toplevel_configure w=%d h=%d\n", width, height);
            std::fclose(f);
        }
    }
    if (width > 0 && height > 0) {
        window->width = width;
        window->height = height;
        if (window->egl_window != nullptr) {
            resize_egl_window(window);
        } else {
            window->resized = true;
        }
    }
    // request_size 用 min=max 逼 compositor 给出目标尺寸；configure 后再放开，
    // 以免长期锁死交互式缩放（对齐「可选清除」）。不可缩放时保持锁定。
    if (window->clear_size_constraints_after_configure &&
        !window->size_locked &&
        window->toplevel != nullptr) {
        window->clear_size_constraints_after_configure = false;
        xdg_toplevel_set_min_size(window->toplevel, 0, 0);
        xdg_toplevel_set_max_size(window->toplevel, 0, 0);
    } else if (window->clear_size_constraints_after_configure) {
        window->clear_size_constraints_after_configure = false;
    }
    try_init_egl_if_needed(window);
    composekn_flush_deferred_frame(window);
}

static void xdg_toplevel_close(void* data, xdg_toplevel* toplevel) {
    (void)toplevel;
    auto* window = static_cast<ComposeKNWindow*>(data);
    // DO_NOTHING：只通知 Kotlin onCloseRequest，不销毁 surface（对齐 Win32 WM_CLOSE）。
    mark_close_requested(window);
}

static void xdg_toplevel_configure_bounds(
    void* data,
    xdg_toplevel* toplevel,
    int32_t width,
    int32_t height
) {
    (void)data;
    (void)toplevel;
    (void)width;
    (void)height;
}

static void xdg_toplevel_wm_capabilities(void* data, xdg_toplevel* toplevel, wl_array* capabilities) {
    (void)data;
    (void)toplevel;
    (void)capabilities;
}

static const xdg_toplevel_listener xdg_toplevel_listener = {
    xdg_toplevel_configure,
    xdg_toplevel_close,
    xdg_toplevel_configure_bounds,
    xdg_toplevel_wm_capabilities,
};

static void zxdg_toplevel_decoration_configure(
    void* data,
    zxdg_toplevel_decoration_v1* decoration,
    uint32_t mode
) {
    (void)decoration;
    auto* window = static_cast<ComposeKNWindow*>(data);
    window->decoration_mode = mode;
}

static const zxdg_toplevel_decoration_v1_listener toplevel_decoration_listener = {
    zxdg_toplevel_decoration_configure,
};

static void request_server_side_decoration(ComposeKNWindow* window) {
    if (window->decoration_manager == nullptr || window->toplevel == nullptr) {
        return;
    }
    window->toplevel_decoration = zxdg_decoration_manager_v1_get_toplevel_decoration(
        window->decoration_manager,
        window->toplevel
    );
    zxdg_toplevel_decoration_v1_add_listener(
        window->toplevel_decoration,
        &toplevel_decoration_listener,
        window
    );
    // Request client-side decoration (CSD) instead of server-side
    zxdg_toplevel_decoration_v1_set_mode(
        window->toplevel_decoration,
        ZXDG_TOPLEVEL_DECORATION_V1_MODE_CLIENT_SIDE
    );
}

static void fractional_scale_preferred_scale(
    void* data,
    wp_fractional_scale_v1* /*fractional_scale*/,
    uint32_t scale
) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    window->scale = static_cast<float>(scale) / 120.0f;
    update_buffer_scale(window);
}

static const wp_fractional_scale_v1_listener fractional_scale_listener = {
    fractional_scale_preferred_scale,
};

static void pointer_enter(
    void* data,
    wl_pointer* pointer,
    uint32_t serial,
    wl_surface* surface,
    wl_fixed_t surface_x,
    wl_fixed_t surface_y
) {
    (void)data;
    (void)pointer;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        g_shared.pointer_focus = nullptr;
        return;
    }
    {
        const char* msg = "composekn: pointer_enter callback fired\n";
        write(2, msg, 42);
    }
    g_shared.pointer_focus = window;
    window->last_serial = serial;
    window->pointer_inside = true;
    window->pointer_x = wl_fixed_to_double(surface_x);
    window->pointer_y = wl_fixed_to_double(surface_y);
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_POINTER_ENTER;
    event.x = static_cast<float>(window->pointer_x);
    event.y = static_cast<float>(window->pointer_y);
    event.modifiers = window->modifiers;
    push_event(window, event);
}

static void pointer_leave(void* data, wl_pointer* pointer, uint32_t serial, wl_surface* surface) {
    (void)data;
    (void)pointer;
    (void)serial;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        window = g_shared.pointer_focus;
    }
    if (window == nullptr) {
        g_shared.pointer_focus = nullptr;
        return;
    }
    window->pointer_inside = false;
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_POINTER_LEAVE;
    push_event(window, event);
    if (g_shared.pointer_focus == window) {
        g_shared.pointer_focus = nullptr;
    }
}

static void pointer_motion(
    void* data,
    wl_pointer* pointer,
    uint32_t time,
    wl_fixed_t surface_x,
    wl_fixed_t surface_y
) {
    (void)data;
    (void)pointer;
    (void)time;
    ComposeKNWindow* window = g_shared.pointer_focus;
    if (window == nullptr) {
        return;
    }
    window->pointer_x = wl_fixed_to_double(surface_x);
    window->pointer_y = wl_fixed_to_double(surface_y);
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_POINTER_MOTION;
    event.x = static_cast<float>(window->pointer_x);
    event.y = static_cast<float>(window->pointer_y);
    event.modifiers = window->modifiers;
    push_event(window, event);
}

static void pointer_button(
    void* data,
    wl_pointer* pointer,
    uint32_t serial,
    uint32_t time,
    uint32_t button,
    uint32_t state
) {
    (void)data;
    (void)pointer;
    (void)time;
    ComposeKNWindow* window = g_shared.pointer_focus;
    if (window == nullptr) {
        return;
    }
    {
        FILE* f = fopen("/tmp/composekn_debug.log", "a");
        if (f) {
            std::fprintf(f, "pointer_button serial=%u btn=%u state=%u local=(%.1f,%.1f)\n",
                         serial, button, state, window->pointer_x, window->pointer_y);
            std::fclose(f);
        }
    }
    window->last_serial = serial;
    if (state == WL_POINTER_BUTTON_STATE_PRESSED) {
        window->pointer_buttons |= (1u << (button - 272));
    } else {
        window->pointer_buttons &= ~(1u << (button - 272));
    }
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_POINTER_BUTTON;
    event.x = static_cast<float>(window->pointer_x);
    event.y = static_cast<float>(window->pointer_y);
    event.button = button;
    event.state = state;
    event.modifiers = window->modifiers;
    push_event(window, event);
}

static void pointer_axis(
    void* data,
    wl_pointer* pointer,
    uint32_t time,
    uint32_t axis,
    wl_fixed_t value
) {
    (void)data;
    (void)pointer;
    (void)time;
    ComposeKNWindow* window = g_shared.pointer_focus;
    if (window == nullptr) {
        return;
    }
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_POINTER_AXIS;
    event.x = static_cast<float>(window->pointer_x);
    event.y = static_cast<float>(window->pointer_y);
    event.axis = static_cast<int32_t>(axis);
    event.axis_value = static_cast<float>(wl_fixed_to_double(value));
    event.modifiers = window->modifiers;
    push_event(window, event);
}

static void pointer_frame(void* data, wl_pointer* pointer) {
    (void)data;
    (void)pointer;
}

static void pointer_axis_source(void* data, wl_pointer* pointer, uint32_t axis_source) {
    (void)data;
    (void)pointer;
    (void)axis_source;
}

static void pointer_axis_stop(void* data, wl_pointer* pointer, uint32_t time, uint32_t axis) {
    (void)data;
    (void)pointer;
    (void)time;
    (void)axis;
}

static void pointer_axis_discrete(void* data, wl_pointer* pointer, uint32_t axis, int32_t discrete) {
    (void)data;
    (void)pointer;
    (void)axis;
    (void)discrete;
}

static void pointer_axis_value120(void* data, wl_pointer* pointer, uint32_t axis, int32_t value120) {
    (void)data;
    (void)pointer;
    (void)axis;
    (void)value120;
}

static void pointer_axis_relative_direction(
    void* data,
    wl_pointer* pointer,
    uint32_t axis,
    uint32_t direction
) {
    (void)data;
    (void)pointer;
    (void)axis;
    (void)direction;
}

static const wl_pointer_listener pointer_listener = {
    pointer_enter,
    pointer_leave,
    pointer_motion,
    pointer_button,
    pointer_axis,
    pointer_frame,
    pointer_axis_source,
    pointer_axis_stop,
    pointer_axis_discrete,
    pointer_axis_value120,
    pointer_axis_relative_direction,
};

// ---- Touch (wl_touch) ----
// The touch id is carried in ComposeKNEvent.button; x/y in surface-local (unscaled) coords.

// Note: this Wayland version's wl_touch has no enter/leave events; a touch
// point is fully described by down (carries the initial position) ... up.
// down also carries the surface; motion carries no serial.

static void touch_down(
    void* data,
    wl_touch* touch,
    uint32_t serial,
    uint32_t time,
    wl_surface* surface,
    int32_t id,
    wl_fixed_t x,
    wl_fixed_t y
) {
    (void)data;
    (void)touch;
    (void)time;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        return;
    }
    {
        FILE* f = fopen("/tmp/composekn_debug.log", "a");
        if (f) {
            std::fprintf(f, "touch_down id=%d x=%.1f y=%.1f\n", id, wl_fixed_to_double(x), wl_fixed_to_double(y));
            std::fclose(f);
        }
    }
    window->last_serial = serial;
    auto pos = std::make_pair(wl_fixed_to_double(x), wl_fixed_to_double(y));
    window->touch_points[static_cast<uint32_t>(id)] = pos;
    touch_route_set(id, window);
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_TOUCH_DOWN;
    event.x = static_cast<float>(pos.first);
    event.y = static_cast<float>(pos.second);
    event.button = static_cast<uint32_t>(id);
    push_event(window, event);
}

static void touch_up(void* data, wl_touch* touch, uint32_t serial, uint32_t time, int32_t id) {
    (void)data;
    (void)touch;
    (void)time;
    (void)serial;
    ComposeKNWindow* window = touch_route_lookup(id);
    if (window == nullptr) {
        return;
    }
    uint32_t tid = static_cast<uint32_t>(id);
    double px = 0.0;
    double py = 0.0;
    auto it = window->touch_points.find(tid);
    if (it != window->touch_points.end()) {
        px = it->second.first;
        py = it->second.second;
    }
    window->touch_points.erase(tid);
    touch_route_clear(id);
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_TOUCH_UP;
    event.x = static_cast<float>(px);
    event.y = static_cast<float>(py);
    event.button = tid;
    push_event(window, event);
}

static void touch_motion(
    void* data,
    wl_touch* touch,
    uint32_t time,
    int32_t id,
    wl_fixed_t x,
    wl_fixed_t y
) {
    (void)data;
    (void)touch;
    (void)time;
    ComposeKNWindow* window = touch_route_lookup(id);
    if (window == nullptr) {
        return;
    }
    auto pos = std::make_pair(wl_fixed_to_double(x), wl_fixed_to_double(y));
    window->touch_points[static_cast<uint32_t>(id)] = pos;
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_TOUCH_MOTION;
    event.x = static_cast<float>(pos.first);
    event.y = static_cast<float>(pos.second);
    event.button = static_cast<uint32_t>(id);
    push_event(window, event);
}

static void touch_frame(void* data, wl_touch* touch) {
    (void)data;
    (void)touch;
}

// The compositor decided this gesture is a global gesture; drop all active touch points.
static void touch_cancel(void* data, wl_touch* touch) {
    (void)data;
    (void)touch;
    for (size_t i = 0; i < g_live_window_count; ++i) {
        g_live_windows[i]->touch_points.clear();
    }
    touch_route_clear_all();
}

// Touch shape / orientation are not used by Compose pointer input.
static void touch_shape(void* data, wl_touch* touch, int32_t id, wl_fixed_t major, wl_fixed_t minor) {
    (void)data;
    (void)touch;
    (void)id;
    (void)major;
    (void)minor;
}

static void touch_orientation(void* data, wl_touch* touch, int32_t id, wl_fixed_t orientation) {
    (void)data;
    (void)touch;
    (void)id;
    (void)orientation;
}

static const wl_touch_listener touch_listener = {
    touch_down,
    touch_up,
    touch_motion,
    touch_frame,
    touch_cancel,
    touch_shape,
    touch_orientation,
};

static void apply_xkb_keymap_to_window(ComposeKNWindow* window, const char* map, uint32_t size) {
    if (window == nullptr || map == nullptr || size == 0) {
        return;
    }
    if (window->kb_state != nullptr) {
        xkb_state_unref(window->kb_state);
        window->kb_state = nullptr;
    }
    if (window->keymap != nullptr) {
        xkb_keymap_unref(window->keymap);
        window->keymap = nullptr;
    }
    if (window->xkb_ctx == nullptr) {
        window->xkb_ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    }
    if (window->xkb_ctx != nullptr) {
        window->keymap = xkb_keymap_new_from_buffer(
            window->xkb_ctx,
            map,
            size,
            XKB_KEYMAP_FORMAT_TEXT_V1,
            XKB_KEYMAP_COMPILE_NO_FLAGS
        );
        if (window->keymap != nullptr) {
            window->kb_state = xkb_state_new(window->keymap);
        }
    }
}

static void clone_xkb_from_window(ComposeKNWindow* dst, ComposeKNWindow* src) {
    if (dst == nullptr || src == nullptr || src->keymap == nullptr) {
        return;
    }
    if (dst->kb_state != nullptr) {
        xkb_state_unref(dst->kb_state);
        dst->kb_state = nullptr;
    }
    if (dst->keymap != nullptr) {
        xkb_keymap_unref(dst->keymap);
        dst->keymap = nullptr;
    }
    if (dst->xkb_ctx == nullptr) {
        dst->xkb_ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    }
    if (dst->xkb_ctx == nullptr) {
        return;
    }
    dst->keymap = xkb_keymap_ref(src->keymap);
    if (dst->keymap != nullptr) {
        dst->kb_state = xkb_state_new(dst->keymap);
    }
    dst->modifiers = src->modifiers;
}

// Keymap often arrives during acquire (before any window is live). Keep a copy for create.
static char* g_pending_keymap = nullptr;
static uint32_t g_pending_keymap_size = 0;

static void store_pending_keymap(const char* map, uint32_t size) {
    if (g_pending_keymap != nullptr) {
        std::free(g_pending_keymap);
        g_pending_keymap = nullptr;
        g_pending_keymap_size = 0;
    }
    if (map == nullptr || size == 0) {
        return;
    }
    g_pending_keymap = static_cast<char*>(std::malloc(size));
    if (g_pending_keymap != nullptr) {
        std::memcpy(g_pending_keymap, map, size);
        g_pending_keymap_size = size;
    }
}

static void apply_pending_keymap_to_window(ComposeKNWindow* window) {
    if (window == nullptr || g_pending_keymap == nullptr || g_pending_keymap_size == 0) {
        return;
    }
    apply_xkb_keymap_to_window(window, g_pending_keymap, g_pending_keymap_size);
}

static void keyboard_keymap(
    void* data,
    wl_keyboard* keyboard,
    uint32_t format,
    int32_t fd,
    uint32_t size
) {
    (void)data;
    (void)keyboard;
    if (format != WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1 || fd < 0 || size == 0) {
        if (fd >= 0) {
            close(fd);
        }
        return;
    }

    char* map = static_cast<char*>(mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (map == MAP_FAILED) {
        return;
    }

    store_pending_keymap(map, size);
    for (size_t i = 0; i < g_live_window_count; ++i) {
        apply_xkb_keymap_to_window(g_live_windows[i], map, size);
    }
    munmap(map, size);
}

static uint32_t xkb_modifiers_to_wl(ComposeKNWindow* window) {
    if (window->kb_state == nullptr || window->keymap == nullptr) {
        return 0;
    }
    uint32_t mods = 0;
    if (xkb_state_mod_name_is_active(
            window->kb_state, XKB_MOD_NAME_SHIFT, XKB_STATE_MODS_EFFECTIVE) > 0) {
        mods |= kWlModShift;
    }
    if (xkb_state_mod_name_is_active(
            window->kb_state, XKB_MOD_NAME_CAPS, XKB_STATE_MODS_EFFECTIVE) > 0) {
        mods |= kWlModCaps;
    }
    if (xkb_state_mod_name_is_active(
            window->kb_state, XKB_MOD_NAME_CTRL, XKB_STATE_MODS_EFFECTIVE) > 0) {
        mods |= kWlModCtrl;
    }
    if (xkb_state_mod_name_is_active(
            window->kb_state, XKB_MOD_NAME_ALT, XKB_STATE_MODS_EFFECTIVE) > 0) {
        mods |= kWlModAlt;
    }
    if (xkb_state_mod_name_is_active(
            window->kb_state, XKB_MOD_NAME_LOGO, XKB_STATE_MODS_EFFECTIVE) > 0) {
        mods |= kWlModMod4;
    }
    return mods;
}

static void keyboard_enter(
    void* data,
    wl_keyboard* keyboard,
    uint32_t serial,
    wl_surface* surface,
    wl_array* keys
) {
    (void)data;
    (void)keyboard;
    (void)keys;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        g_shared.keyboard_focus = nullptr;
        return;
    }
    g_shared.keyboard_focus = window;
    window->last_serial = serial;
    if (window->keymap == nullptr) {
        apply_pending_keymap_to_window(window);
    }
    ComposeKNEvent focus{};
    focus.type = COMPOSEKN_EVENT_FOCUS;
    focus.state = 1;
    push_event(window, focus);
}

static void keyboard_leave(void* data, wl_keyboard* keyboard, uint32_t serial, wl_surface* surface) {
    (void)data;
    (void)keyboard;
    (void)serial;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        window = g_shared.keyboard_focus;
    }
    if (g_shared.keyboard_focus == window) {
        g_shared.keyboard_focus = nullptr;
    }
    if (window != nullptr) {
        ComposeKNEvent focus{};
        focus.type = COMPOSEKN_EVENT_FOCUS;
        focus.state = 0;
        push_event(window, focus);
    }
}

static uint32_t wayland_key_to_evdev(uint32_t key) {
    // Wayland protocol: wl_keyboard.key delivers (evdev keycode - 8).
    // Restore the evdev keycode by adding the offset back.
    return key + kWaylandToEvdevOffset;
}

static void keyboard_key(
    void* data,
    wl_keyboard* keyboard,
    uint32_t serial,
    uint32_t time,
    uint32_t key,
    uint32_t state
) {
    (void)data;
    (void)keyboard;
    (void)serial;
    (void)time;
    ComposeKNWindow* window = g_shared.keyboard_focus;
    if (window == nullptr) {
        return;
    }
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_KEY;
    event.key_code = wayland_key_to_evdev(key);
    event.state = state;
    event.modifiers = window->modifiers;
    event.keysym = 0;
    if (window->kb_state != nullptr) {
        xkb_state_update_key(
            window->kb_state,
            key,
            state == WL_KEYBOARD_KEY_STATE_PRESSED ? XKB_KEY_DOWN : XKB_KEY_UP
        );
        if (state == WL_KEYBOARD_KEY_STATE_PRESSED) {
            event.keysym = static_cast<uint32_t>(xkb_state_key_get_one_sym(window->kb_state, key));
        }
    }
    push_event(window, event);
}

static void keyboard_modifiers(
    void* data,
    wl_keyboard* keyboard,
    uint32_t serial,
    uint32_t mods_depressed,
    uint32_t mods_latched,
    uint32_t mods_locked,
    uint32_t group
) {
    (void)data;
    (void)keyboard;
    ComposeKNWindow* window = g_shared.keyboard_focus;
    if (window == nullptr) {
        // Still update all live windows' modifier masks when possible.
        for (size_t i = 0; i < g_live_window_count; ++i) {
            ComposeKNWindow* w = g_live_windows[i];
            w->last_serial = serial;
            if (w->kb_state != nullptr) {
                xkb_state_update_mask(
                    w->kb_state,
                    mods_depressed,
                    mods_latched,
                    mods_locked,
                    0,
                    0,
                    group
                );
                w->modifiers = xkb_modifiers_to_wl(w);
            } else {
                w->modifiers = mods_depressed | mods_latched | mods_locked;
            }
        }
        return;
    }
    window->last_serial = serial;
    if (window->kb_state != nullptr) {
        xkb_state_update_mask(
            window->kb_state,
            mods_depressed,
            mods_latched,
            mods_locked,
            0,
            0,
            group
        );
        window->modifiers = xkb_modifiers_to_wl(window);
    } else {
        window->modifiers = mods_depressed | mods_latched | mods_locked;
    }
}

static void keyboard_repeat_info(void* data, wl_keyboard* keyboard, int32_t rate, int32_t delay) {
    (void)data;
    (void)keyboard;
    (void)rate;
    (void)delay;
}

// ---------------------------------------------------------------------------
// Clipboard / DnD helpers (multi-MIME wl_data_source / wl_data_offer)
// ---------------------------------------------------------------------------

struct OfferMimeState {
    std::vector<std::string> mimes;
    bool has_text = false;
    bool has_html = false;
    bool has_rtf = false;
    bool has_image = false;
    bool has_uri = false;
};

/** Inbound DnD target while pointer is over a surface (leave/motion/drop lack surface). */
static ComposeKNWindow* g_dnd_target = nullptr;

static void classify_mime(OfferMimeState* state, const char* mime_type) {
    if (state == nullptr || mime_type == nullptr) {
        return;
    }
    state->mimes.emplace_back(mime_type);
    if (strstr(mime_type, "text/plain") != nullptr) {
        state->has_text = true;
    }
    if (strcmp(mime_type, "text/html") == 0) {
        state->has_html = true;
    }
    if (strcmp(mime_type, "text/rtf") == 0 || strcmp(mime_type, "application/rtf") == 0) {
        state->has_rtf = true;
    }
    if (strncmp(mime_type, "image/", 6) == 0) {
        state->has_image = true;
    }
    if (strcmp(mime_type, "text/uri-list") == 0) {
        state->has_uri = true;
    }
}

static void apply_mime_state_to_selection(ComposeKNWindow* window, const OfferMimeState& state) {
    window->selection_mimes = state.mimes;
    window->selection_has_text = state.has_text;
    window->selection_has_html = state.has_html;
    window->selection_has_rtf = state.has_rtf;
    window->selection_has_image = state.has_image;
    window->selection_has_uri = state.has_uri;
}

static void clear_selection_caches(ComposeKNWindow* window) {
    window->clipboard_cache.clear();
    window->clipboard_html_cache.clear();
    window->clipboard_rtf_cache.clear();
    window->clipboard_files_cache.clear();
    window->clipboard_image_bgra.clear();
    window->clipboard_image_w = 0;
    window->clipboard_image_h = 0;
}

static std::string percent_encode_path(const std::string& path) {
    static const char* kHex = "0123456789ABCDEF";
    std::string out;
    out.reserve(path.size() + 16);
    for (unsigned char c : path) {
        if (std::isalnum(c) || c == '/' || c == '-' || c == '_' || c == '.' || c == '~') {
            out.push_back(static_cast<char>(c));
        } else {
            out.push_back('%');
            out.push_back(kHex[(c >> 4) & 0xF]);
            out.push_back(kHex[c & 0xF]);
        }
    }
    return out;
}

static int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static std::string percent_decode(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); ++i) {
        if (in[i] == '%' && i + 2 < in.size()) {
            const int hi = hex_nibble(in[i + 1]);
            const int lo = hex_nibble(in[i + 2]);
            if (hi >= 0 && lo >= 0) {
                out.push_back(static_cast<char>((hi << 4) | lo));
                i += 2;
                continue;
            }
        }
        out.push_back(in[i]);
    }
    return out;
}

static std::string paths_to_uri_list(const char* paths_joined) {
    if (paths_joined == nullptr || paths_joined[0] == '\0') {
        return {};
    }
    std::string out;
    const char* p = paths_joined;
    while (*p) {
        while (*p == '\n' || *p == '\r') ++p;
        if (!*p) break;
        const char* start = p;
        while (*p && *p != '\n' && *p != '\r') ++p;
        std::string path(start, p);
        if (path.empty()) continue;
        out += "file://";
        if (!path.empty() && path[0] != '/') {
            out += '/';
        }
        out += percent_encode_path(path);
        out += "\r\n";
    }
    return out;
}

static std::string uri_list_to_paths(const std::string& uri_list) {
    std::string out;
    size_t i = 0;
    while (i < uri_list.size()) {
        size_t end = uri_list.find_first_of("\r\n", i);
        if (end == std::string::npos) end = uri_list.size();
        std::string line = uri_list.substr(i, end - i);
        i = end;
        while (i < uri_list.size() && (uri_list[i] == '\r' || uri_list[i] == '\n')) ++i;
        if (line.empty() || line[0] == '#') continue;
        // Trim trailing whitespace
        while (!line.empty() && (line.back() == ' ' || line.back() == '\t')) line.pop_back();
        std::string path;
        if (line.compare(0, 8, "file:///") == 0) {
            path = percent_decode(line.substr(7)); // keep leading /
        } else if (line.compare(0, 7, "file://") == 0) {
            // file://host/path or file://path
            size_t slash = line.find('/', 7);
            if (slash == std::string::npos) continue;
            path = percent_decode(line.substr(slash));
        } else if (!line.empty() && line[0] == '/') {
            path = percent_decode(line);
        } else {
            continue;
        }
        if (path.empty()) continue;
        if (!out.empty()) out.push_back('\n');
        out += path;
    }
    return out;
}

#pragma pack(push, 1)
struct ComposeKNBmpFileHeader {
    uint16_t bfType;
    uint32_t bfSize;
    uint16_t bfReserved1;
    uint16_t bfReserved2;
    uint32_t bfOffBits;
};
struct ComposeKNBmpInfoHeader {
    uint32_t biSize;
    int32_t biWidth;
    int32_t biHeight;
    uint16_t biPlanes;
    uint16_t biBitCount;
    uint32_t biCompression;
    uint32_t biSizeImage;
    int32_t biXPelsPerMeter;
    int32_t biYPelsPerMeter;
    uint32_t biClrUsed;
    uint32_t biClrImportant;
};
#pragma pack(pop)

static std::vector<uint8_t> encode_bmp_bgra(int32_t w, int32_t h, const uint8_t* bgra) {
    std::vector<uint8_t> empty;
    if (w <= 0 || h <= 0 || bgra == nullptr) return empty;
    const int32_t stride = ((w * 3 + 3) / 4) * 4; // 24bpp row padded
    const uint32_t pixel_bytes = static_cast<uint32_t>(stride) * static_cast<uint32_t>(h);
    const uint32_t off = sizeof(ComposeKNBmpFileHeader) + sizeof(ComposeKNBmpInfoHeader);
    std::vector<uint8_t> out(off + pixel_bytes, 0);
    auto* fh = reinterpret_cast<ComposeKNBmpFileHeader*>(out.data());
    fh->bfType = 0x4D42;
    fh->bfSize = static_cast<uint32_t>(out.size());
    fh->bfOffBits = off;
    auto* ih = reinterpret_cast<ComposeKNBmpInfoHeader*>(out.data() + sizeof(ComposeKNBmpFileHeader));
    ih->biSize = sizeof(ComposeKNBmpInfoHeader);
    ih->biWidth = w;
    ih->biHeight = h; // bottom-up
    ih->biPlanes = 1;
    ih->biBitCount = 24;
    ih->biCompression = 0;
    ih->biSizeImage = pixel_bytes;
    for (int32_t y = 0; y < h; ++y) {
        const uint8_t* src = bgra + static_cast<size_t>(y) * static_cast<size_t>(w) * 4u;
        uint8_t* dst = out.data() + off + static_cast<size_t>(h - 1 - y) * static_cast<size_t>(stride);
        for (int32_t x = 0; x < w; ++x) {
            dst[x * 3 + 0] = src[x * 4 + 0]; // B
            dst[x * 3 + 1] = src[x * 4 + 1]; // G
            dst[x * 3 + 2] = src[x * 4 + 2]; // R
        }
    }
    return out;
}

static bool decode_bmp_to_bgra(
    const uint8_t* bytes,
    size_t len,
    std::vector<uint8_t>& out_bgra,
    int32_t& out_w,
    int32_t& out_h
) {
    out_bgra.clear();
    out_w = 0;
    out_h = 0;
    if (bytes == nullptr || len < sizeof(ComposeKNBmpFileHeader) + sizeof(ComposeKNBmpInfoHeader)) {
        return false;
    }
    const auto* fh = reinterpret_cast<const ComposeKNBmpFileHeader*>(bytes);
    if (fh->bfType != 0x4D42) return false;
    const auto* ih = reinterpret_cast<const ComposeKNBmpInfoHeader*>(bytes + sizeof(ComposeKNBmpFileHeader));
    if (ih->biSize < 40) return false;
    int32_t w = ih->biWidth;
    int32_t h_raw = ih->biHeight;
    bool top_down = h_raw < 0;
    int32_t h = top_down ? -h_raw : h_raw;
    if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return false;
    const uint16_t bpp = ih->biBitCount;
    if (bpp != 24 && bpp != 32) return false;
    if (ih->biCompression != 0 && !(bpp == 32 && ih->biCompression == 3)) return false;
    uint32_t off = fh->bfOffBits;
    if (off == 0) off = static_cast<uint32_t>(sizeof(ComposeKNBmpFileHeader) + ih->biSize);
    if (off >= len) return false;
    const int32_t row_bytes = ((w * (bpp / 8) + 3) / 4) * 4;
    if (off + static_cast<uint32_t>(row_bytes) * static_cast<uint32_t>(h) > len) return false;
    out_bgra.resize(static_cast<size_t>(w) * static_cast<size_t>(h) * 4u);
    for (int32_t y = 0; y < h; ++y) {
        const int32_t src_y = top_down ? y : (h - 1 - y);
        const uint8_t* src = bytes + off + static_cast<size_t>(src_y) * static_cast<size_t>(row_bytes);
        uint8_t* dst = out_bgra.data() + static_cast<size_t>(y) * static_cast<size_t>(w) * 4u;
        for (int32_t x = 0; x < w; ++x) {
            if (bpp == 24) {
                dst[x * 4 + 0] = src[x * 3 + 0];
                dst[x * 4 + 1] = src[x * 3 + 1];
                dst[x * 4 + 2] = src[x * 3 + 2];
                dst[x * 4 + 3] = 255;
            } else {
                dst[x * 4 + 0] = src[x * 4 + 0];
                dst[x * 4 + 1] = src[x * 4 + 1];
                dst[x * 4 + 2] = src[x * 4 + 2];
                dst[x * 4 + 3] = src[x * 4 + 3];
            }
        }
    }
    out_w = w;
    out_h = h;
    return true;
}

static std::string receive_offer_mime(
    ComposeKNWindow* window,
    wl_data_offer* offer,
    const char* mime
) {
    std::string result;
    if (window == nullptr || offer == nullptr || mime == nullptr || window->display == nullptr) {
        return result;
    }
    int fds[2];
    if (pipe(fds) != 0) {
        return result;
    }
    wl_data_offer_receive(offer, mime, fds[1]);
    wl_display_flush(window->display);
    close(fds[1]);

    struct pollfd pfd{};
    pfd.fd = fds[0];
    pfd.events = POLLIN;
    if (poll(&pfd, 1, 2000) <= 0) {
        close(fds[0]);
        return result;
    }
    char buf[4096];
    ssize_t bytes_read;
    while ((bytes_read = read(fds[0], buf, sizeof(buf))) > 0) {
        result.append(buf, static_cast<size_t>(bytes_read));
    }
    close(fds[0]);
    return result;
}

static void data_offer_offer(void* data, wl_data_offer* offer, const char* mime_type) {
    (void)offer;
    classify_mime(static_cast<OfferMimeState*>(data), mime_type);
}

static void data_offer_source_actions(void* data, wl_data_offer* offer, uint32_t source_actions) {
    (void)data;
    (void)offer;
    (void)source_actions;
}

static void data_offer_action(void* data, wl_data_offer* offer, uint32_t dnd_action) {
    (void)data;
    (void)offer;
    (void)dnd_action;
}

static const wl_data_offer_listener data_offer_listener = {
    data_offer_offer,
    data_offer_source_actions,
    data_offer_action,
};

static void read_selection_into_cache(ComposeKNWindow* window) {
    if (window == nullptr || window->selection_offer == nullptr) {
        return;
    }
    clear_selection_caches(window);

    if (window->selection_has_text) {
        std::string text = receive_offer_mime(window, window->selection_offer, "text/plain;charset=utf-8");
        if (text.empty()) {
            text = receive_offer_mime(window, window->selection_offer, "text/plain");
        }
        window->clipboard_cache = std::move(text);
    }
    if (window->selection_has_html) {
        window->clipboard_html_cache =
            receive_offer_mime(window, window->selection_offer, "text/html");
    }
    if (window->selection_has_rtf) {
        std::string rtf = receive_offer_mime(window, window->selection_offer, "text/rtf");
        if (rtf.empty()) {
            rtf = receive_offer_mime(window, window->selection_offer, "application/rtf");
        }
        window->clipboard_rtf_cache = std::move(rtf);
    }
    if (window->selection_has_uri) {
        const std::string uri =
            receive_offer_mime(window, window->selection_offer, "text/uri-list");
        window->clipboard_files_cache = uri_list_to_paths(uri);
    }
    if (window->selection_has_image) {
        // Prefer BMP (what we offer); also try png bytes only if labeled image/bmp miss.
        std::string bmp = receive_offer_mime(window, window->selection_offer, "image/bmp");
        if (bmp.empty()) {
            // Some apps offer image/png — skip decode (no png decoder here).
            for (const auto& mime : window->selection_mimes) {
                if (mime == "image/bmp" || mime == "image/x-bmp" || mime == "image/x-ms-bmp") {
                    bmp = receive_offer_mime(window, window->selection_offer, mime.c_str());
                    if (!bmp.empty()) break;
                }
            }
        }
        if (!bmp.empty()) {
            int32_t w = 0, h = 0;
            std::vector<uint8_t> bgra;
            if (decode_bmp_to_bgra(
                    reinterpret_cast<const uint8_t*>(bmp.data()), bmp.size(), bgra, w, h
                )) {
                window->clipboard_image_bgra = std::move(bgra);
                window->clipboard_image_w = w;
                window->clipboard_image_h = h;
            }
        }
    }
}

static void composekn_process_deferred_selection(ComposeKNWindow* window) {
    if (window == nullptr || !window->selection_read_pending) {
        return;
    }
    window->selection_read_pending = false;
    if (window->selection_has_text || window->selection_has_html || window->selection_has_rtf ||
        window->selection_has_image || window->selection_has_uri) {
        read_selection_into_cache(window);
    }
}

static void data_device_data_offer(void* data, wl_data_device* data_device, wl_data_offer* id) {
    (void)data;
    (void)data_device;
    if (id == nullptr) return;
    // Attach listener immediately so offer MIME events in this dispatch are captured.
    auto* state = new OfferMimeState();
    wl_data_offer_add_listener(id, &data_offer_listener, state);
}

static OfferMimeState* take_offer_mime_state(wl_data_offer* offer) {
    if (offer == nullptr) return nullptr;
    // Re-bind listener with null state would lose data; instead we rely on the
    // listener user pointer still being the OfferMimeState allocated in data_offer.
    // wayland-client stores it on the proxy — recover via wl_proxy_get_user_data.
    void* ud = wl_proxy_get_user_data(reinterpret_cast<wl_proxy*>(offer));
    auto* state = static_cast<OfferMimeState*>(ud);
    // Keep pointer on proxy for destroy cleanup; caller deletes after copy.
    return state;
}

static void free_offer_mime_state(wl_data_offer* offer) {
    if (offer == nullptr) return;
    void* ud = wl_proxy_get_user_data(reinterpret_cast<wl_proxy*>(offer));
    delete static_cast<OfferMimeState*>(ud);
    wl_proxy_set_user_data(reinterpret_cast<wl_proxy*>(offer), nullptr);
}

static void dnd_accept_preferred(ComposeKNWindow* window) {
    if (window == nullptr || window->dnd_offer == nullptr) return;
    const char* mime = nullptr;
    if (window->dnd_accept) {
        if (window->dnd_has_uri) mime = "text/uri-list";
        else if (window->dnd_has_text) mime = "text/plain;charset=utf-8";
    }
    wl_data_offer_accept(window->dnd_offer, window->dnd_serial, mime);
}

static void data_device_enter(
    void* data,
    wl_data_device* data_device,
    uint32_t serial,
    wl_surface* surface,
    wl_fixed_t x,
    wl_fixed_t y,
    wl_data_offer* id
) {
    (void)data;
    (void)data_device;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        if (id != nullptr) {
            free_offer_mime_state(id);
            wl_data_offer_destroy(id);
        }
        return;
    }
    if (window->dnd_offer != nullptr && window->dnd_offer != id) {
        free_offer_mime_state(window->dnd_offer);
        wl_data_offer_destroy(window->dnd_offer);
        window->dnd_offer = nullptr;
    }
    window->dnd_offer = id;
    window->dnd_serial = serial;
    window->dnd_x = wl_fixed_to_double(x);
    window->dnd_y = wl_fixed_to_double(y);
    window->dnd_has_text = false;
    window->dnd_has_uri = false;
    window->dnd_files_cache.clear();
    window->dnd_text_cache.clear();
    window->dnd_accept = false;
    g_dnd_target = window;

    OfferMimeState* state = take_offer_mime_state(id);
    if (state != nullptr) {
        window->dnd_has_text = state->has_text;
        window->dnd_has_uri = state->has_uri;
    }
    // Optimistic accept when payload looks usable (mirrors Win32 DropTarget).
    if (window->dnd_has_uri || window->dnd_has_text) {
        window->dnd_accept = true;
    }
    if (id != nullptr) {
        wl_data_offer_set_actions(
            id,
            WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY,
            WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY
        );
        dnd_accept_preferred(window);
    }

    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_DRAG_ENTER;
    event.x = static_cast<float>(window->dnd_x);
    event.y = static_cast<float>(window->dnd_y);
    // button bits: 1=uri-list, 2=text (payload only on Drop)
    event.button = (window->dnd_has_uri ? 1u : 0u) | (window->dnd_has_text ? 2u : 0u);
    push_event(window, event);
}

static void data_device_leave(void* data, wl_data_device* data_device) {
    (void)data;
    (void)data_device;
    ComposeKNWindow* window = g_dnd_target;
    if (window == nullptr) return;
    if (window->dnd_offer != nullptr) {
        free_offer_mime_state(window->dnd_offer);
        wl_data_offer_destroy(window->dnd_offer);
        window->dnd_offer = nullptr;
    }
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_DRAG_LEAVE;
    event.x = static_cast<float>(window->dnd_x);
    event.y = static_cast<float>(window->dnd_y);
    push_event(window, event);
    g_dnd_target = nullptr;
}

static void data_device_motion(
    void* data,
    wl_data_device* data_device,
    uint32_t time,
    wl_fixed_t x,
    wl_fixed_t y
) {
    (void)data;
    (void)data_device;
    (void)time;
    ComposeKNWindow* window = g_dnd_target;
    if (window == nullptr) return;
    window->dnd_x = wl_fixed_to_double(x);
    window->dnd_y = wl_fixed_to_double(y);
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_DRAG_OVER;
    event.x = static_cast<float>(window->dnd_x);
    event.y = static_cast<float>(window->dnd_y);
    event.button = (window->dnd_has_uri ? 1u : 0u) | (window->dnd_has_text ? 2u : 0u);
    push_event(window, event);
}

static void data_device_drop(void* data, wl_data_device* data_device) {
    (void)data;
    (void)data_device;
    ComposeKNWindow* window = g_dnd_target;
    if (window == nullptr || window->dnd_offer == nullptr) return;

    if (window->dnd_has_uri) {
        const std::string uri =
            receive_offer_mime(window, window->dnd_offer, "text/uri-list");
        window->dnd_files_cache = uri_list_to_paths(uri);
    }
    if (window->dnd_has_text) {
        std::string text =
            receive_offer_mime(window, window->dnd_offer, "text/plain;charset=utf-8");
        if (text.empty()) {
            text = receive_offer_mime(window, window->dnd_offer, "text/plain");
        }
        window->dnd_text_cache = std::move(text);
    }

    wl_data_offer_finish(window->dnd_offer);

    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_DRAG_DROP;
    event.x = static_cast<float>(window->dnd_x);
    event.y = static_cast<float>(window->dnd_y);
    event.button = (window->dnd_has_uri ? 1u : 0u) | (window->dnd_has_text ? 2u : 0u);
    push_event(window, event);

    free_offer_mime_state(window->dnd_offer);
    wl_data_offer_destroy(window->dnd_offer);
    window->dnd_offer = nullptr;
    g_dnd_target = nullptr;
}

static void data_device_selection(
    void* data,
    wl_data_device* data_device,
    wl_data_offer* offer
) {
    (void)data;
    (void)data_device;
    ComposeKNWindow* window = clipboard_target_window();
    if (window == nullptr) {
        if (offer != nullptr) {
            free_offer_mime_state(offer);
            wl_data_offer_destroy(offer);
        }
        return;
    }
    if (window->selection_offer != nullptr && window->selection_offer != offer) {
        free_offer_mime_state(window->selection_offer);
        wl_data_offer_destroy(window->selection_offer);
    }
    window->selection_offer = offer;
    window->selection_has_text = false;
    window->selection_has_html = false;
    window->selection_has_rtf = false;
    window->selection_has_image = false;
    window->selection_has_uri = false;
    window->selection_mimes.clear();
    window->selection_read_pending = false;
    if (offer != nullptr) {
        OfferMimeState* state = take_offer_mime_state(offer);
        if (state != nullptr) {
            apply_mime_state_to_selection(window, *state);
        }
        window->selection_read_pending = true;
    } else {
        clear_selection_caches(window);
    }
}

static const wl_data_device_listener data_device_listener = {
    data_device_data_offer,
    data_device_enter,
    data_device_leave,
    data_device_motion,
    data_device_drop,
    data_device_selection,
};

static void write_fd_bytes(int32_t fd, const void* data, size_t size) {
    if (fd < 0 || data == nullptr || size == 0) {
        if (fd >= 0) close(fd);
        return;
    }
    const uint8_t* p = static_cast<const uint8_t*>(data);
    size_t left = size;
    while (left > 0) {
        ssize_t n = write(fd, p, left);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        p += static_cast<size_t>(n);
        left -= static_cast<size_t>(n);
    }
    close(fd);
}

static void data_source_handle_target(void* data, wl_data_source* source, const char* mime_type) {
    (void)data;
    (void)source;
    (void)mime_type;
}

static void data_source_handle_send(
    void* data,
    wl_data_source* source,
    const char* mime_type,
    int32_t fd
) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window == nullptr || mime_type == nullptr) {
        if (fd >= 0) close(fd);
        return;
    }
    const bool is_drag = (window->drag_source == source);

    if (strstr(mime_type, "text/plain") != nullptr) {
        const std::string& text = is_drag ? window->drag_set_text : window->clipboard_set_pending;
        write_fd_bytes(fd, text.data(), text.size());
        return;
    }
    if (strcmp(mime_type, "text/html") == 0) {
        write_fd_bytes(fd, window->clipboard_set_html.data(), window->clipboard_set_html.size());
        return;
    }
    if (strcmp(mime_type, "text/rtf") == 0 || strcmp(mime_type, "application/rtf") == 0) {
        write_fd_bytes(fd, window->clipboard_set_rtf.data(), window->clipboard_set_rtf.size());
        return;
    }
    if (strcmp(mime_type, "text/uri-list") == 0) {
        const std::string& uri = is_drag ? window->drag_set_files_uri : window->clipboard_set_files_uri;
        write_fd_bytes(fd, uri.data(), uri.size());
        return;
    }
    if (strcmp(mime_type, "image/bmp") == 0 || strcmp(mime_type, "image/x-bmp") == 0 ||
        strcmp(mime_type, "image/x-ms-bmp") == 0) {
        write_fd_bytes(fd, window->clipboard_set_bmp.data(), window->clipboard_set_bmp.size());
        return;
    }
    close(fd);
}

static void cleanup_drag_icon(ComposeKNWindow* window) {
    if (window == nullptr) return;
    if (window->drag_icon_buffer != nullptr) {
        wl_buffer_destroy(window->drag_icon_buffer);
        window->drag_icon_buffer = nullptr;
    }
    if (window->drag_icon_surface != nullptr) {
        wl_surface_destroy(window->drag_icon_surface);
        window->drag_icon_surface = nullptr;
    }
    if (window->drag_icon_shm != nullptr && window->drag_icon_shm_size > 0) {
        munmap(window->drag_icon_shm, window->drag_icon_shm_size);
        window->drag_icon_shm = nullptr;
        window->drag_icon_shm_size = 0;
    }
    if (window->drag_icon_fd >= 0) {
        close(window->drag_icon_fd);
        window->drag_icon_fd = -1;
    }
}

static void finish_outbound_drag(ComposeKNWindow* window, int32_t result) {
    if (window == nullptr) return;
    cleanup_drag_icon(window);
    if (window->drag_source != nullptr) {
        wl_data_source_destroy(window->drag_source);
        window->drag_source = nullptr;
    }
    window->drag_active = false;
    window->drag_drop_performed = false;
    window->drag_result = result;
    window->drag_set_text.clear();
    window->drag_set_files_uri.clear();
    window->drag_icon_bgra.clear();
    // Button release often swallowed by compositor grab — match beginMove.
    synth_left_button_up_if_pressed(window, "dragEnd");
}

static void data_source_handle_cancelled(void* data, wl_data_source* source) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window == nullptr) return;
    if (window->drag_source == source) {
        finish_outbound_drag(window, 0);
        return;
    }
    if (window->clipboard_source == source) {
        wl_data_source_destroy(source);
        window->clipboard_source = nullptr;
    }
}

static void data_source_handle_dnd_drop_performed(void* data, wl_data_source* source) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window == nullptr || window->drag_source != source) return;
    window->drag_drop_performed = true;
}

static void data_source_handle_dnd_finished(void* data, wl_data_source* source) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window == nullptr || window->drag_source != source) return;
    finish_outbound_drag(window, 1);
}

static void data_source_handle_action(void* data, wl_data_source* source, uint32_t dnd_action) {
    (void)data;
    (void)source;
    (void)dnd_action;
}

static const wl_data_source_listener data_source_listener = {
    data_source_handle_target,
    data_source_handle_send,
    data_source_handle_cancelled,
    data_source_handle_dnd_drop_performed,
    data_source_handle_dnd_finished,
    data_source_handle_action,
};

static void ensure_data_device() {
    if (g_shared.data_device != nullptr) {
        return;
    }
    if (g_shared.data_device_manager == nullptr || g_shared.seat == nullptr) {
        return;
    }
    g_shared.data_device =
        wl_data_device_manager_get_data_device(g_shared.data_device_manager, g_shared.seat);
    wl_data_device_add_listener(g_shared.data_device, &data_device_listener, &g_shared);
    refresh_all_window_shared_aliases();
}

static int create_shm_file(size_t size) {
#if defined(__linux__) && defined(SYS_memfd_create)
    {
        int fd = static_cast<int>(syscall(SYS_memfd_create, "composekn-dnd-icon", MFD_CLOEXEC));
        if (fd >= 0) {
            if (ftruncate(fd, static_cast<off_t>(size)) == 0) return fd;
            close(fd);
        }
    }
#endif
    char template_path[] = "/tmp/composekn-shm-XXXXXX";
    int fd = mkstemp(template_path);
    if (fd < 0) return -1;
    unlink(template_path);
    if (ftruncate(fd, static_cast<off_t>(size)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static bool create_drag_icon_surface(ComposeKNWindow* window) {
    if (window == nullptr || window->compositor == nullptr || window->shm == nullptr) {
        return false;
    }
    if (window->drag_icon_w <= 0 || window->drag_icon_h <= 0 || window->drag_icon_bgra.empty()) {
        return false;
    }
    const int32_t w = window->drag_icon_w;
    const int32_t h = window->drag_icon_h;
    const size_t stride = static_cast<size_t>(w) * 4u;
    const size_t size = stride * static_cast<size_t>(h);
    if (window->drag_icon_bgra.size() < size) return false;

    int fd = create_shm_file(size);
    if (fd < 0) return false;
    void* map = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        close(fd);
        return false;
    }
    // Wayland ARGB8888 little-endian == BGRA byte order.
    std::memcpy(map, window->drag_icon_bgra.data(), size);

    wl_shm_pool* pool = wl_shm_create_pool(window->shm, fd, static_cast<int32_t>(size));
    if (pool == nullptr) {
        munmap(map, size);
        close(fd);
        return false;
    }
    wl_buffer* buffer = wl_shm_pool_create_buffer(
        pool, 0, w, h, static_cast<int32_t>(stride), WL_SHM_FORMAT_ARGB8888
    );
    wl_shm_pool_destroy(pool);
    if (buffer == nullptr) {
        munmap(map, size);
        close(fd);
        return false;
    }
    wl_surface* surface = wl_compositor_create_surface(window->compositor);
    if (surface == nullptr) {
        wl_buffer_destroy(buffer);
        munmap(map, size);
        close(fd);
        return false;
    }
    // Hotspot = surface origin: attach buffer so (hot_x, hot_y) maps to (0, 0).
    // compositor is bound at v4 (attach x/y still valid; offset req is v5+).
    wl_surface_attach(surface, buffer, -window->drag_hot_x, -window->drag_hot_y);
    wl_surface_damage(surface, 0, 0, w, h);
    wl_surface_commit(surface);

    window->drag_icon_fd = fd;
    window->drag_icon_shm = map;
    window->drag_icon_shm_size = size;
    window->drag_icon_buffer = buffer;
    window->drag_icon_surface = surface;
    return true;
}

static int32_t copy_bytes_result(const void* src, size_t len, char* buf, int32_t size) {
    if (len == 0) return 0;
    const int32_t needed = static_cast<int32_t>(len);
    if (buf == nullptr || size < needed) return needed;
    std::memcpy(buf, src, len);
    return needed;
}

static int32_t copy_string_result(const std::string& s, char* buf, int32_t size) {
    return copy_bytes_result(s.data(), s.size(), buf, size);
}

/* ---- IME (zwp_text_input_v3) event handlers ---- */
static void ti_enter(void* data, zwp_text_input_v3*, wl_surface* surface) {
    (void)data;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        window = g_shared.keyboard_focus;
    }
    if (window == nullptr) {
        return;
    }
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_ENTER;
    push_ime_event(window, ev);
}
static void ti_leave(void* data, zwp_text_input_v3*, wl_surface* surface) {
    (void)data;
    ComposeKNWindow* window = surface_map_lookup(surface);
    if (window == nullptr) {
        window = g_shared.keyboard_focus;
    }
    if (window == nullptr) {
        return;
    }
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_LEAVE;
    push_ime_event(window, ev);
}
static void ti_preedit_string(void* data, zwp_text_input_v3*, const char* text, int32_t cursor_begin, int32_t cursor_end) {
    (void)data;
    ComposeKNWindow* window = g_shared.keyboard_focus;
    if (window == nullptr) {
        return;
    }
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_PREEDIT;
    ev.text = (text != nullptr) ? text : "";
    ev.a = static_cast<uint32_t>(cursor_begin < 0 ? 0 : cursor_begin);
    ev.b = static_cast<uint32_t>(cursor_end < 0 ? 0 : cursor_end);
    push_ime_event(window, ev);
}
static void ti_commit_string(void* data, zwp_text_input_v3*, const char* text) {
    (void)data;
    ComposeKNWindow* window = g_shared.keyboard_focus;
    if (window == nullptr) {
        return;
    }
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_COMMIT;
    ev.text = (text != nullptr) ? text : "";
    push_ime_event(window, ev);
}
static void ti_delete_surrounding_text(void* data, zwp_text_input_v3*, uint32_t before_length, uint32_t after_length) {
    (void)data;
    ComposeKNWindow* window = g_shared.keyboard_focus;
    if (window == nullptr) {
        return;
    }
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_DELETE;
    ev.a = before_length;
    ev.b = after_length;
    push_ime_event(window, ev);
}
static void ti_done(void* data, zwp_text_input_v3*, uint32_t) {
    (void)data;
    ComposeKNWindow* window = g_shared.keyboard_focus;
    if (window == nullptr) {
        return;
    }
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_DONE;
    push_ime_event(window, ev);
}
static void ti_action(void*, zwp_text_input_v3*, uint32_t, uint32_t) {}
static void ti_language(void*, zwp_text_input_v3*, const char*) {}
static void ti_preedit_hint(void*, zwp_text_input_v3*, uint32_t, uint32_t, uint32_t) {}

static const zwp_text_input_v3_listener text_input_listener = {
    ti_enter,
    ti_leave,
    ti_preedit_string,
    ti_commit_string,
    ti_delete_surrounding_text,
    ti_done,
    ti_action,
    ti_language,
    ti_preedit_hint,
};

static void ensure_text_input() {
    if (g_shared.text_input != nullptr) {
        return;
    }
    if (g_shared.text_input_manager == nullptr || g_shared.seat == nullptr) {
        return;
    }
    g_shared.text_input =
        zwp_text_input_manager_v3_get_text_input(g_shared.text_input_manager, g_shared.seat);
    zwp_text_input_v3_add_listener(g_shared.text_input, &text_input_listener, &g_shared);
    refresh_all_window_shared_aliases();
}

static const wl_keyboard_listener keyboard_listener = {
    keyboard_keymap,
    keyboard_enter,
    keyboard_leave,
    keyboard_key,
    keyboard_modifiers,
    keyboard_repeat_info,
};

static void seat_capabilities(void* data, wl_seat* seat, uint32_t capabilities) {
    (void)data;
    {
        FILE* f = fopen("/tmp/composekn_debug.log", "a");
        if (f) {
            std::fprintf(f, "seat capabilities=0x%x pointer=%d keyboard=%d touch=%d\n",
                         capabilities,
                         (capabilities & WL_SEAT_CAPABILITY_POINTER) ? 1 : 0,
                         (capabilities & WL_SEAT_CAPABILITY_KEYBOARD) ? 1 : 0,
                         (capabilities & WL_SEAT_CAPABILITY_TOUCH) ? 1 : 0);
            std::fclose(f);
        }
    }
    if ((capabilities & WL_SEAT_CAPABILITY_POINTER) && g_shared.pointer == nullptr) {
        g_shared.pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(g_shared.pointer, &pointer_listener, &g_shared);
    } else if (!(capabilities & WL_SEAT_CAPABILITY_POINTER) && g_shared.pointer != nullptr) {
        wl_pointer_destroy(g_shared.pointer);
        g_shared.pointer = nullptr;
        g_shared.pointer_focus = nullptr;
    }
    if ((capabilities & WL_SEAT_CAPABILITY_KEYBOARD) && g_shared.keyboard == nullptr) {
        g_shared.keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(g_shared.keyboard, &keyboard_listener, &g_shared);
    } else if (!(capabilities & WL_SEAT_CAPABILITY_KEYBOARD) && g_shared.keyboard != nullptr) {
        wl_keyboard_destroy(g_shared.keyboard);
        g_shared.keyboard = nullptr;
        g_shared.keyboard_focus = nullptr;
    }
    if ((capabilities & WL_SEAT_CAPABILITY_TOUCH) && g_shared.touch == nullptr) {
        g_shared.touch = wl_seat_get_touch(seat);
        wl_touch_add_listener(g_shared.touch, &touch_listener, &g_shared);
        fprintf(stderr, "composekn: wl_touch object created\n");
        fflush(stderr);
    } else if (!(capabilities & WL_SEAT_CAPABILITY_TOUCH) && g_shared.touch != nullptr) {
        wl_touch_release(g_shared.touch);
        g_shared.touch = nullptr;
        for (size_t i = 0; i < g_live_window_count; ++i) {
            g_live_windows[i]->touch_points.clear();
        }
        touch_route_clear_all();
    }
    ensure_data_device();
    if (capabilities & WL_SEAT_CAPABILITY_KEYBOARD) {
        ensure_text_input();
    }
    refresh_all_window_shared_aliases();
}

static void seat_name(void* data, wl_seat* seat, const char* name) {
    (void)data;
    (void)seat;
    (void)name;
}

static const wl_seat_listener seat_listener = {
    seat_capabilities,
    seat_name,
};

static int64_t monotonic_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

static void composekn_request_frame_internal(ComposeKNWindow* window);

/**
 * Headless / 慢 WSI：wl_surface_frame 可能永不回调（空 commit 无 buffer）。
 * 超时后合成 FRAME，避免 FrameRecomposer 卡死、selftest 挂住。
 */
static void composekn_kick_stale_frame(ComposeKNWindow* window, int timeout_ms) {
    if (window == nullptr || !window->frame_pending || window->frame_armed_ns == 0) {
        return;
    }
    const int64_t limit = static_cast<int64_t>(timeout_ms) * 1000000LL;
    if (monotonic_ns() - window->frame_armed_ns < limit) {
        return;
    }
    if (window->frame_callback != nullptr) {
        wl_callback_destroy(window->frame_callback);
        window->frame_callback = nullptr;
    }
    window->frame_pending = false;
    window->frame_armed_ns = 0;
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_FRAME;
    push_event(window, event);
}

static void frame_done(void* data, wl_callback* callback, uint32_t time) {
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window->frame_callback == callback) {
        window->frame_callback = nullptr;
    }
    wl_callback_destroy(callback);
    window->frame_pending = false;
    window->frame_armed_ns = 0;
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_FRAME;
    push_event(window, event);
    if (window->frame_requested) {
        window->frame_requested = false;
    }
}

static const wl_callback_listener frame_listener = {
    frame_done,
};

static bool init_egl(ComposeKNWindow* window) {
    if (!g_shared.egl_initialized) {
        g_shared.egl_display = eglGetDisplay(static_cast<EGLNativeDisplayType>(g_shared.display));
        if (g_shared.egl_display == EGL_NO_DISPLAY) {
            std::fprintf(stderr, "composekn: eglGetDisplay failed\n");
            return false;
        }

        EGLint major = 0;
        EGLint minor = 0;
        if (!eglInitialize(g_shared.egl_display, &major, &minor)) {
            std::fprintf(stderr, "composekn: eglInitialize failed\n");
            return false;
        }

        if (!eglBindAPI(EGL_OPENGL_ES_API)) {
            std::fprintf(stderr, "composekn: eglBindAPI(EGL_OPENGL_ES_API) failed\n");
            return false;
        }

        const EGLint config_attribs_es3[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 8,
            EGL_NONE,
        };
        const EGLint config_attribs_es2[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_STENCIL_SIZE, 8,
            EGL_NONE,
        };

        EGLint num_configs = 0;
        if (eglChooseConfig(g_shared.egl_display, config_attribs_es3, &g_shared.egl_config, 1, &num_configs) &&
            num_configs > 0) {
            g_shared.egl_context_client_version = 3;
        } else if (eglChooseConfig(g_shared.egl_display, config_attribs_es2, &g_shared.egl_config, 1, &num_configs) &&
                   num_configs > 0) {
            g_shared.egl_context_client_version = 2;
        } else {
            std::fprintf(stderr, "composekn: eglChooseConfig failed\n");
            return false;
        }
        g_shared.egl_initialized = true;
        refresh_all_window_shared_aliases();
    }

    window->egl_display = g_shared.egl_display;
    window->egl_config = g_shared.egl_config;

    window->egl_window = wl_egl_window_create(
        window->surface,
        physical_width(window),
        physical_height(window)
    );
    if (window->egl_window == nullptr) {
        std::fprintf(stderr, "composekn: wl_egl_window_create failed\n");
        return false;
    }

    window->egl_surface = eglCreateWindowSurface(
        window->egl_display,
        window->egl_config,
        reinterpret_cast<EGLNativeWindowType>(window->egl_window),
        nullptr
    );
    if (window->egl_surface == EGL_NO_SURFACE) {
        std::fprintf(stderr, "composekn: eglCreateWindowSurface failed\n");
        return false;
    }

    if (g_shared.egl_context == EGL_NO_CONTEXT) {
        const EGLint context_attribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, g_shared.egl_context_client_version,
            EGL_NONE,
        };
        g_shared.egl_context = eglCreateContext(
            window->egl_display,
            window->egl_config,
            EGL_NO_CONTEXT,
            context_attribs
        );
        if (g_shared.egl_context == EGL_NO_CONTEXT) {
            std::fprintf(stderr, "composekn: eglCreateContext failed (ES %d)\n",
                         g_shared.egl_context_client_version);
            return false;
        }
    }
    window->egl_context = g_shared.egl_context;

    return true;
}

static void try_init_egl_if_needed(ComposeKNWindow* window) {
    if (window == nullptr || window->egl_ready || !window->configured) {
        return;
    }
    if (window->prefer_vulkan) {
        return;
    }
    if (window->width <= 0 || window->height <= 0) {
        return;
    }
    if (!init_egl(window)) {
        std::fprintf(stderr, "composekn: EGL init failed after configure\n");
        mark_close_requested(window);
        return;
    }
    window->egl_ready = true;
    eglMakeCurrent(window->egl_display, window->egl_surface, window->egl_surface, window->egl_context);
    eglSwapInterval(window->egl_display, 1);
    std::fprintf(stderr, "composekn: EGL ready %dx%d\n", window->width, window->height);
    window->frame_requested = true;
}

static bool acquire_shared_display() {
    if (g_shared.refcount > 0) {
        // Already connected — bump ref only when the shared objects are still valid.
        if (g_shared.display == nullptr || g_shared.compositor == nullptr || g_shared.wm_base == nullptr) {
            return false;
        }
        ++g_shared.refcount;
        return true;
    }

    const char* display_name = std::getenv("WAYLAND_DISPLAY");
    g_shared.display = wl_display_connect(display_name);
    if (g_shared.display == nullptr) {
        std::fprintf(stderr, "composekn: wl_display_connect failed (WAYLAND_DISPLAY=%s)\n",
                     display_name ? display_name : "(unset)");
        return false;
    }

    g_shared.registry = wl_display_get_registry(g_shared.display);
    wl_registry_add_listener(g_shared.registry, &registry_listener, &g_shared);
    wl_display_roundtrip(g_shared.display);

    if (g_shared.compositor == nullptr || g_shared.wm_base == nullptr) {
        std::fprintf(stderr, "composekn: missing compositor or xdg_wm_base\n");
        // refcount still 0 — release tears down the partial connection.
        release_shared_display();
        return false;
    }

    xdg_wm_base_add_listener(g_shared.wm_base, &xdg_wm_base_listener, &g_shared);

    if (g_shared.seat != nullptr) {
        wl_seat_add_listener(g_shared.seat, &seat_listener, &g_shared);
        // Dispatch seat capabilities (creates pointer/keyboard/touch).
        wl_display_roundtrip(g_shared.display);
    }

    ensure_data_device();
    ensure_text_input();

    g_shared.refcount = 1;
    return true;
}

static void release_shared_display() {
    if (g_shared.refcount > 0) {
        --g_shared.refcount;
    }
    if (g_shared.refcount > 0) {
        return;
    }

    g_shared.pointer_focus = nullptr;
    g_shared.keyboard_focus = nullptr;
    touch_route_clear_all();

    // Seat-bound objects first (text_input / data_device / pointer / …), then seat.
    if (g_shared.text_input != nullptr) {
        zwp_text_input_v3_destroy(g_shared.text_input);
        g_shared.text_input = nullptr;
    }
    if (g_shared.text_input_manager != nullptr) {
        zwp_text_input_manager_v3_destroy(g_shared.text_input_manager);
        g_shared.text_input_manager = nullptr;
    }
    if (g_shared.data_device != nullptr) {
        wl_data_device_destroy(g_shared.data_device);
        g_shared.data_device = nullptr;
    }
    if (g_shared.data_device_manager != nullptr) {
        wl_data_device_manager_destroy(g_shared.data_device_manager);
        g_shared.data_device_manager = nullptr;
    }
    if (g_shared.shm != nullptr) {
        wl_shm_destroy(g_shared.shm);
        g_shared.shm = nullptr;
    }
    if (g_shared.pointer != nullptr) {
        wl_pointer_destroy(g_shared.pointer);
        g_shared.pointer = nullptr;
    }
    if (g_shared.keyboard != nullptr) {
        wl_keyboard_destroy(g_shared.keyboard);
        g_shared.keyboard = nullptr;
    }
    if (g_shared.touch != nullptr) {
        wl_touch_release(g_shared.touch);
        g_shared.touch = nullptr;
    }
    if (g_shared.seat != nullptr) {
        wl_seat_destroy(g_shared.seat);
        g_shared.seat = nullptr;
    }
    if (g_shared.decoration_manager != nullptr) {
        zxdg_decoration_manager_v1_destroy(g_shared.decoration_manager);
        g_shared.decoration_manager = nullptr;
    }
    if (g_shared.fractional_scale_manager != nullptr) {
        wp_fractional_scale_manager_v1_destroy(g_shared.fractional_scale_manager);
        g_shared.fractional_scale_manager = nullptr;
    }
    if (g_shared.egl_initialized && g_shared.egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_shared.egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_shared.egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_shared.egl_display, g_shared.egl_context);
            g_shared.egl_context = EGL_NO_CONTEXT;
        }
        eglTerminate(g_shared.egl_display);
    }
    g_shared.egl_display = EGL_NO_DISPLAY;
    g_shared.egl_config = nullptr;
    g_shared.egl_context = EGL_NO_CONTEXT;
    g_shared.egl_initialized = false;
    g_shared.egl_context_client_version = 2;

    if (g_shared.wm_base != nullptr) {
        xdg_wm_base_destroy(g_shared.wm_base);
        g_shared.wm_base = nullptr;
    }
    // compositor is released with the display connection.
    g_shared.compositor = nullptr;
    if (g_shared.registry != nullptr) {
        wl_registry_destroy(g_shared.registry);
        g_shared.registry = nullptr;
    }
    if (g_shared.display != nullptr) {
        wl_display_disconnect(g_shared.display);
        g_shared.display = nullptr;
    }
    if (g_pending_keymap != nullptr) {
        std::free(g_pending_keymap);
        g_pending_keymap = nullptr;
        g_pending_keymap_size = 0;
    }
    g_shared.refcount = 0;
    g_display_read_this_cycle = false;
}

extern "C" void composekn_display_begin_poll_cycle(void) {
    g_display_read_this_cycle = false;
}

extern "C" ComposeKNWindow* composekn_window_create(const char* title, int width, int height) {
    auto* window = new ComposeKNWindow();
    window->width = width > 0 ? width : 800;
    window->height = height > 0 ? height : 600;

    if (!acquire_shared_display()) {
        delete window;
        return nullptr;
    }
    alias_shared_onto_window(window);
    // Insert early so destroy() can tear down create-failure paths safely.
    live_window_insert(window);

    if (window->compositor == nullptr || window->wm_base == nullptr) {
        std::fprintf(stderr, "composekn: missing compositor or xdg_wm_base\n");
        composekn_window_destroy(window);
        return nullptr;
    }

    window->surface = wl_compositor_create_surface(window->compositor);
    // Fractional scale disabled — see registry_global.

    window->shell_surface = xdg_wm_base_get_xdg_surface(window->wm_base, window->surface);
    xdg_surface_add_listener(window->shell_surface, &xdg_surface_listener, window);

    window->toplevel = xdg_surface_get_toplevel(window->shell_surface);
    xdg_toplevel_add_listener(window->toplevel, &xdg_toplevel_listener, window);
    xdg_toplevel_set_title(window->toplevel, title != nullptr ? title : "ComposeKN");
    xdg_toplevel_set_app_id(window->toplevel, "com.composekn.demo");
    request_server_side_decoration(window);

    surface_map_insert(window->surface, window);

    // Default Graphite/Vulkan：先占住 wl_surface，避免 configure 抢 EGL。
    // COMPOSEKN_RENDER_API=gl|opengl 时走 GLES；Vulkan 创建失败由 Kotlin 清 prefer_vulkan。
    window->prefer_vulkan = true;
    if (const char* api = std::getenv("COMPOSEKN_RENDER_API")) {
        if (std::strcmp(api, "gl") == 0 || std::strcmp(api, "opengl") == 0 ||
            std::strcmp(api, "gles") == 0 || std::strcmp(api, "software") == 0 ||
            std::strcmp(api, "sw") == 0) {
            window->prefer_vulkan = false;
        }
    }

    wl_surface_commit(window->surface);
    wl_display_roundtrip(window->display);
    try_init_egl_if_needed(window);
    composekn_flush_deferred_frame(window);

    apply_pending_keymap_to_window(window);
    if (window->keymap == nullptr && g_primary_window != nullptr && g_primary_window != window) {
        clone_xkb_from_window(window, g_primary_window);
    }

    g_primary_window = window;
    return window;
}

extern "C" void composekn_window_destroy(ComposeKNWindow* window) {
    if (window == nullptr) {
        return;
    }
    // destroy 后 Kotlin 仍可能持有野指针；拒绝已释放窗口（不解引用字段）。
    if (!live_window_contains(window)) {
        return;
    }
    live_window_erase(window);

    if (g_shared.pointer_focus == window) {
        g_shared.pointer_focus = nullptr;
    }
    if (g_shared.keyboard_focus == window) {
        g_shared.keyboard_focus = nullptr;
    }
    touch_route_clear_window(window);
    surface_map_erase(window);

    if (g_primary_window == window) {
        g_primary_window = (g_live_window_count > 0) ? g_live_windows[0] : nullptr;
    }

    // Tear down Vulkan before EGL / wl_surface (swapchain holds the surface).
    composekn_window_vk_destroy(window);

    if (window->clipboard_source != nullptr) {
        wl_data_source_destroy(window->clipboard_source);
        window->clipboard_source = nullptr;
    }
    if (window->drag_source != nullptr) {
        cleanup_drag_icon(window);
        wl_data_source_destroy(window->drag_source);
        window->drag_source = nullptr;
        window->drag_active = false;
    } else {
        cleanup_drag_icon(window);
    }
    if (window->selection_offer != nullptr) {
        free_offer_mime_state(window->selection_offer);
        wl_data_offer_destroy(window->selection_offer);
        window->selection_offer = nullptr;
    }
    if (window->dnd_offer != nullptr) {
        free_offer_mime_state(window->dnd_offer);
        wl_data_offer_destroy(window->dnd_offer);
        window->dnd_offer = nullptr;
    }
    if (g_dnd_target == window) {
        g_dnd_target = nullptr;
    }

    if (window->frame_callback != nullptr) {
        wl_callback_destroy(window->frame_callback);
        window->frame_callback = nullptr;
    }
    if (window->fractional_scale != nullptr) {
        wp_fractional_scale_v1_destroy(window->fractional_scale);
        window->fractional_scale = nullptr;
    }

    // Per-window EGL surface/window only — shared EGLContext/Display until last window.
    if (window->egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(window->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    // window->egl_context aliases g_shared.egl_context — do not destroy here.
    window->egl_context = EGL_NO_CONTEXT;
    if (window->egl_surface != EGL_NO_SURFACE) {
        eglDestroySurface(window->egl_display, window->egl_surface);
        window->egl_surface = EGL_NO_SURFACE;
    }
    if (window->egl_window != nullptr) {
        wl_egl_window_destroy(window->egl_window);
        window->egl_window = nullptr;
    }
    window->egl_ready = false;

    if (window->toplevel_decoration != nullptr) {
        zxdg_toplevel_decoration_v1_destroy(window->toplevel_decoration);
        window->toplevel_decoration = nullptr;
    }
    if (window->toplevel != nullptr) {
        xdg_toplevel_destroy(window->toplevel);
        window->toplevel = nullptr;
    }
    if (window->shell_surface != nullptr) {
        xdg_surface_destroy(window->shell_surface);
        window->shell_surface = nullptr;
    }
    if (window->surface != nullptr) {
        wl_surface_destroy(window->surface);
        window->surface = nullptr;
    }

    if (window->kb_state != nullptr) {
        xkb_state_unref(window->kb_state);
        window->kb_state = nullptr;
    }
    if (window->keymap != nullptr) {
        xkb_keymap_unref(window->keymap);
        window->keymap = nullptr;
    }
    if (window->xkb_ctx != nullptr) {
        xkb_context_unref(window->xkb_ctx);
        window->xkb_ctx = nullptr;
    }

    clear_window_shared_aliases(window);
    release_shared_display();
    delete window;
}

extern "C" bool composekn_window_poll(ComposeKNWindow* window) {
    ComposeKNWindow* w = resolve_window(window);
    if (w == nullptr) {
        return false;
    }
    // DO_NOTHING：close_requested 后仍继续派发 Wayland 事件，直到 destroy。
    // 关窗通知走 composekn_window_consume_close_requested，不靠 poll 返回 false。

    w->in_dispatch = true;

    if (!g_display_read_this_cycle) {
        g_display_read_this_cycle = true;
        if (wl_display_prepare_read(w->display) != 0) {
            if (wl_display_dispatch_pending(w->display) < 0) {
                w->in_dispatch = false;
                return false;
            }
        } else {
            if (wl_display_flush(w->display) < 0 && errno != EAGAIN) {
                wl_display_cancel_read(w->display);
                w->in_dispatch = false;
                return false;
            }
            wl_display_read_events(w->display);
            if (wl_display_dispatch_pending(w->display) < 0) {
                w->in_dispatch = false;
                return false;
            }
        }
    } else {
        // Another window already read this cycle; just drain any remaining pending.
        if (wl_display_dispatch_pending(w->display) < 0) {
            w->in_dispatch = false;
            return false;
        }
    }

    w->in_dispatch = false;
    composekn_flush_deferred_frame(w);
    // Headless weston 等：空 commit 的 frame callback 可能永不回来。
    composekn_kick_stale_frame(w, 32);
    composekn_process_deferred_selection(w);
    // Single-window / direct poll paths never call begin_poll_cycle; clear so the
    // next poll can prepare_read again. Multi-window host clears via begin_poll_cycle.
    if (g_live_window_count <= 1) {
        g_display_read_this_cycle = false;
    }
    return true;
}

extern "C" bool composekn_window_pop_event(ComposeKNWindow* window, ComposeKNEvent* out) {
    window = resolve_window(window);
    if (window == nullptr || out == nullptr || window->events.empty()) {
        return false;
    }
    *out = window->events.front();
    window->events.pop_front();
    return true;
}

extern "C" bool composekn_window_pop_event_flat(
    ComposeKNWindow* window,
    int32_t* type,
    float* x,
    float* y,
    uint32_t* button,
    uint32_t* state,
    int32_t* axis,
    float* axis_value,
    uint32_t* key_code,
    uint32_t* keysym,
    uint32_t* modifiers,
    float* scale
) {
    window = resolve_window(window);
    ComposeKNEvent event{};
    if (!composekn_window_pop_event(window, &event)) {
        return false;
    }
    if (type) *type = event.type;
    if (x) *x = event.x;
    if (y) *y = event.y;
    if (button) *button = event.button;
    if (state) *state = event.state;
    if (axis) *axis = event.axis;
    if (axis_value) *axis_value = event.axis_value;
    if (key_code) *key_code = event.key_code;
    if (keysym) *keysym = event.keysym;
    if (modifiers) *modifiers = event.modifiers;
    if (scale) *scale = event.scale;
    return true;
}

static void composekn_request_frame_internal(ComposeKNWindow* window) {
    if (window == nullptr || window->surface == nullptr || window->display == nullptr) {
        return;
    }
    window->frame_requested = true;
}

static void composekn_flush_deferred_frame(ComposeKNWindow* window) {
    if (window == nullptr || window->surface == nullptr || window->display == nullptr) {
        return;
    }
    // GLES 要等 egl_ready；Graphite/Vulkan 走 prefer_vulkan（跳过 EGL）。
    // 若只认 egl_ready，Vulkan 永远发不出 wl_surface_frame → 无 FRAME →
    // FrameRecomposer 卡在 withFrameNanos，exitApplication 无法拆窗。
    if (!window->configured || window->width <= 0 || window->height <= 0) {
        return;
    }
    if (!window->egl_ready && !window->prefer_vulkan) {
        return;
    }
    if (window->in_dispatch || window->frame_callback != nullptr) {
        return;
    }
    if (!window->frame_requested) {
        return;
    }
    window->frame_requested = false;
    window->frame_pending = true;
    window->frame_armed_ns = monotonic_ns();
    window->frame_callback = wl_surface_frame(window->surface);
    if (window->frame_callback == nullptr) {
        std::fprintf(stderr, "composekn: wl_surface_frame returned null\n");
        window->frame_pending = false;
        window->frame_armed_ns = 0;
        window->frame_requested = true;
        return;
    }
    wl_callback_add_listener(window->frame_callback, &frame_listener, window);
    wl_surface_commit(window->surface);
}

extern "C" void composekn_window_request_frame(ComposeKNWindow* window) {
    ComposeKNWindow* w = resolve_window(window);
    composekn_request_frame_internal(w);
    composekn_flush_deferred_frame(w);
}

extern "C" bool composekn_window_frame_pending(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr && window->frame_pending;
}

extern "C" void composekn_window_make_current(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr) {
        return;
    }
    if (!window->egl_ready) {
        try_init_egl_if_needed(window);
    }
    if (window->egl_display == EGL_NO_DISPLAY || window->egl_context == EGL_NO_CONTEXT) {
        return;
    }
    if (!eglMakeCurrent(window->egl_display, window->egl_surface, window->egl_surface, window->egl_context)) {
        std::fprintf(stderr, "composekn: eglMakeCurrent failed (error 0x%x)\n", eglGetError());
        std::fflush(stderr);
    }
}

extern "C" void composekn_window_swap_buffers(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr || window->egl_display == EGL_NO_DISPLAY || !window->egl_ready) {
        return;
    }
    if (!window->configured || window->width <= 0 || window->height <= 0) {
        return;
    }
    eglSwapBuffers(window->egl_display, window->egl_surface);
    composekn_request_frame_internal(window);
    composekn_flush_deferred_frame(window);
}

extern "C" void composekn_window_set_swap_interval(ComposeKNWindow* window, int interval) {
    window = resolve_window(window);
    if (window == nullptr || window->egl_display == EGL_NO_DISPLAY) {
        return;
    }
    eglSwapInterval(window->egl_display, interval);
}

extern "C" int composekn_window_width(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr ? sanitize_dimension(window->width) : 0;
}

extern "C" int composekn_window_height(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr ? sanitize_dimension(window->height) : 0;
}

extern "C" float composekn_window_scale(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr) {
        return 1.0f;
    }
    const float scale = window->scale;
    return (scale >= 1.0f && scale <= 4.0f) ? scale : 1.0f;
}

extern "C" int composekn_window_buffer_width(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr ? physical_width(window) : 0;
}

extern "C" int composekn_window_buffer_height(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr ? physical_height(window) : 0;
}

extern "C" void* composekn_window_wl_display(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr ? static_cast<void*>(window->display) : nullptr;
}

extern "C" void* composekn_window_wl_surface(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr ? static_cast<void*>(window->surface) : nullptr;
}

extern "C" void composekn_window_set_vulkan_preferred(ComposeKNWindow* window, bool preferred) {
    window = resolve_window(window);
    if (window == nullptr) {
        return;
    }
    window->prefer_vulkan = preferred;
}

extern "C" bool composekn_window_vulkan_preferred(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr && window->prefer_vulkan;
}

extern "C" bool composekn_window_consume_resized(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr || !window->resized) {
        return false;
    }
    window->resized = false;
    return true;
}

extern "C" int composekn_gl_get_draw_framebuffer_binding(void) {
    GLint fb = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fb);
    return fb;
}

extern "C" bool composekn_clipboard_get_text(char* buffer, size_t buffer_size) {
    ComposeKNWindow* window = clipboard_target_window();
    if (window == nullptr || buffer == nullptr || buffer_size == 0) {
        return false;
    }
    composekn_process_deferred_selection(window);
    const std::string& text = window->clipboard_cache;
    strncpy(buffer, text.c_str(), buffer_size - 1);
    buffer[buffer_size - 1] = '\0';
    return true;
}

extern "C" void composekn_clipboard_set_rich(
    const char* utf8_text,
    const char* utf8_html,
    const char* utf8_rtf,
    int32_t image_w,
    int32_t image_h,
    const uint8_t* bgra,
    const char* utf8_files
) {
    ComposeKNWindow* window = clipboard_target_window();
    if (window == nullptr || window->data_device_manager == nullptr) {
        return;
    }

    window->clipboard_set_pending = utf8_text != nullptr ? utf8_text : "";
    window->clipboard_set_html = utf8_html != nullptr ? utf8_html : "";
    window->clipboard_set_rtf = utf8_rtf != nullptr ? utf8_rtf : "";
    window->clipboard_set_files_uri = paths_to_uri_list(utf8_files);
    window->clipboard_set_bmp.clear();
    window->clipboard_set_bgra.clear();
    window->clipboard_set_image_w = 0;
    window->clipboard_set_image_h = 0;

    // Local caches so get* works without a round-trip.
    window->clipboard_cache = window->clipboard_set_pending;
    window->clipboard_html_cache = window->clipboard_set_html;
    window->clipboard_rtf_cache = window->clipboard_set_rtf;
    window->clipboard_files_cache = utf8_files != nullptr ? utf8_files : "";
    // Normalize files cache to '\n' paths (uri_list round-trip strips empties).
    if (!window->clipboard_set_files_uri.empty()) {
        window->clipboard_files_cache = uri_list_to_paths(window->clipboard_set_files_uri);
    }
    window->clipboard_image_bgra.clear();
    window->clipboard_image_w = 0;
    window->clipboard_image_h = 0;
    // Avoid a deferred selection receive overwriting the local caches we just set.
    window->selection_read_pending = false;

    if (image_w > 0 && image_h > 0 && bgra != nullptr) {
        const size_t nbytes = static_cast<size_t>(image_w) * static_cast<size_t>(image_h) * 4u;
        window->clipboard_set_bgra.assign(bgra, bgra + nbytes);
        window->clipboard_set_bmp = encode_bmp_bgra(image_w, image_h, bgra);
        window->clipboard_set_image_w = image_w;
        window->clipboard_set_image_h = image_h;
        window->clipboard_image_bgra = window->clipboard_set_bgra;
        window->clipboard_image_w = image_w;
        window->clipboard_image_h = image_h;
    }

    if (window->clipboard_source != nullptr) {
        wl_data_source_destroy(window->clipboard_source);
        window->clipboard_source = nullptr;
    }
    window->clipboard_source =
        wl_data_device_manager_create_data_source(window->data_device_manager);
    if (window->clipboard_source == nullptr) return;
    wl_data_source_add_listener(window->clipboard_source, &data_source_listener, window);

    if (utf8_text != nullptr) {
        wl_data_source_offer(window->clipboard_source, "text/plain;charset=utf-8");
        wl_data_source_offer(window->clipboard_source, "text/plain");
    }
    if (!window->clipboard_set_html.empty()) {
        wl_data_source_offer(window->clipboard_source, "text/html");
    }
    if (!window->clipboard_set_rtf.empty()) {
        wl_data_source_offer(window->clipboard_source, "text/rtf");
        wl_data_source_offer(window->clipboard_source, "application/rtf");
    }
    if (!window->clipboard_set_files_uri.empty()) {
        wl_data_source_offer(window->clipboard_source, "text/uri-list");
    }
    if (!window->clipboard_set_bmp.empty()) {
        wl_data_source_offer(window->clipboard_source, "image/bmp");
    }

    ensure_data_device();
    alias_shared_onto_window(window);
    if (window->data_device != nullptr) {
        wl_data_device_set_selection(
            window->data_device, window->clipboard_source, window->last_serial
        );
    }
}

extern "C" void composekn_clipboard_set_text(const char* text) {
    composekn_clipboard_set_rich(text, nullptr, nullptr, 0, 0, nullptr, nullptr);
}

extern "C" int32_t composekn_clipboard_get_html(char* buf, int32_t size) {
    ComposeKNWindow* window = clipboard_target_window();
    if (window == nullptr) return 0;
    composekn_process_deferred_selection(window);
    return copy_string_result(window->clipboard_html_cache, buf, size);
}

extern "C" int32_t composekn_clipboard_get_rtf(char* buf, int32_t size) {
    ComposeKNWindow* window = clipboard_target_window();
    if (window == nullptr) return 0;
    composekn_process_deferred_selection(window);
    return copy_string_result(window->clipboard_rtf_cache, buf, size);
}

extern "C" int32_t composekn_clipboard_get_files(char* buf, int32_t size) {
    ComposeKNWindow* window = clipboard_target_window();
    if (window == nullptr) return 0;
    composekn_process_deferred_selection(window);
    return copy_string_result(window->clipboard_files_cache, buf, size);
}

extern "C" int32_t composekn_clipboard_get_image(uint8_t* bgra, int32_t size, int32_t* out_dims) {
    ComposeKNWindow* window = clipboard_target_window();
    if (window == nullptr) return 0;
    composekn_process_deferred_selection(window);
    const auto& pixels = window->clipboard_image_bgra;
    if (pixels.empty() || window->clipboard_image_w <= 0 || window->clipboard_image_h <= 0) {
        return 0;
    }
    if (out_dims != nullptr) {
        out_dims[0] = window->clipboard_image_w;
        out_dims[1] = window->clipboard_image_h;
    }
    const int32_t needed = static_cast<int32_t>(pixels.size());
    if (bgra == nullptr || size < needed) return needed;
    std::memcpy(bgra, pixels.data(), pixels.size());
    return needed;
}

extern "C" bool composekn_window_start_drag(
    ComposeKNWindow* window,
    const char* utf8_files,
    const char* utf8_text,
    int32_t icon_w,
    int32_t icon_h,
    const uint8_t* icon_bgra,
    int32_t hot_x,
    int32_t hot_y
) {
    window = resolve_window(window);
    if (window == nullptr || window->data_device_manager == nullptr || window->surface == nullptr) {
        return false;
    }
    ensure_data_device();
    alias_shared_onto_window(window);
    if (window->data_device == nullptr) return false;
    if (window->drag_active) return false;

    window->drag_set_text = utf8_text != nullptr ? utf8_text : "";
    window->drag_set_files_uri = paths_to_uri_list(utf8_files);
    if (window->drag_set_text.empty() && window->drag_set_files_uri.empty()) {
        return false;
    }

    if (window->drag_source != nullptr) {
        wl_data_source_destroy(window->drag_source);
        window->drag_source = nullptr;
    }
    cleanup_drag_icon(window);

    window->drag_source =
        wl_data_device_manager_create_data_source(window->data_device_manager);
    if (window->drag_source == nullptr) return false;
    wl_data_source_add_listener(window->drag_source, &data_source_listener, window);

    if (!window->drag_set_text.empty()) {
        wl_data_source_offer(window->drag_source, "text/plain;charset=utf-8");
        wl_data_source_offer(window->drag_source, "text/plain");
    }
    if (!window->drag_set_files_uri.empty()) {
        wl_data_source_offer(window->drag_source, "text/uri-list");
    }
    wl_data_source_set_actions(window->drag_source, WL_DATA_DEVICE_MANAGER_DND_ACTION_COPY);

    window->drag_icon_w = 0;
    window->drag_icon_h = 0;
    window->drag_hot_x = hot_x;
    window->drag_hot_y = hot_y;
    window->drag_icon_bgra.clear();
    wl_surface* icon_surface = nullptr;
    if (icon_w > 0 && icon_h > 0 && icon_bgra != nullptr) {
        const size_t nbytes = static_cast<size_t>(icon_w) * static_cast<size_t>(icon_h) * 4u;
        window->drag_icon_bgra.assign(icon_bgra, icon_bgra + nbytes);
        window->drag_icon_w = icon_w;
        window->drag_icon_h = icon_h;
        if (create_drag_icon_surface(window)) {
            icon_surface = window->drag_icon_surface;
        }
    }

    window->drag_result = -1;
    window->drag_drop_performed = false;
    window->drag_active = true;

    wl_data_device_start_drag(
        window->data_device,
        window->drag_source,
        window->surface,
        icon_surface,
        window->last_serial
    );
    wl_display_flush(window->display);

    // start_drag returns immediately while compositor owns the grab — clear
    // primaryPressed like beginMove (Windows synths after DoDragDrop returns).
    synth_left_button_up_if_pressed(window, "startDrag");
    return true;
}

extern "C" int32_t composekn_window_drag_poll_result(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr) return -1;
    if (window->drag_active) return -1;
    const int32_t result = window->drag_result;
    if (result == 0 || result == 1) {
        window->drag_result = -1; // clear once consumed
    }
    return result;
}

extern "C" void composekn_window_dnd_set_accept(ComposeKNWindow* window, bool accept) {
    window = resolve_window(window);
    if (window == nullptr) return;
    window->dnd_accept = accept;
    dnd_accept_preferred(window);
}

extern "C" int32_t composekn_window_dnd_pop_files(ComposeKNWindow* window, char* buf, int32_t size) {
    window = resolve_window(window);
    if (window == nullptr) return 0;
    const int32_t n = copy_string_result(window->dnd_files_cache, buf, size);
    if (buf != nullptr && n > 0 && size >= n) {
        window->dnd_files_cache.clear();
    }
    return n;
}

extern "C" int32_t composekn_window_dnd_pop_text(ComposeKNWindow* window, char* buf, int32_t size) {
    window = resolve_window(window);
    if (window == nullptr) return 0;
    const int32_t n = copy_string_result(window->dnd_text_cache, buf, size);
    if (buf != nullptr && n > 0 && size >= n) {
        window->dnd_text_cache.clear();
    }
    return n;
}

extern "C" bool composekn_window_uses_server_decoration(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr) {
        return false;
    }
    return window->decoration_mode == ZXDG_TOPLEVEL_DECORATION_V1_MODE_SERVER_SIDE;
}

extern "C" void composekn_window_minimize(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr) {
        return;
    }
    xdg_toplevel_set_minimized(window->toplevel);
}

extern "C" void composekn_window_toggle_maximized(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr) {
        return;
    }
    if (window->maximized) {
        xdg_toplevel_unset_maximized(window->toplevel);
    } else {
        xdg_toplevel_set_maximized(window->toplevel);
    }
}

extern "C" bool composekn_window_is_maximized(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr && window->maximized;
}

extern "C" void composekn_window_set_fullscreen(ComposeKNWindow* window, bool enable) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr) {
        return;
    }
    if (enable) {
        xdg_toplevel_set_fullscreen(window->toplevel, nullptr);
    } else {
        xdg_toplevel_unset_fullscreen(window->toplevel);
    }
    if (window->surface != nullptr) {
        wl_surface_commit(window->surface);
    }
    if (window->display != nullptr) {
        wl_display_flush(window->display);
    }
}

extern "C" bool composekn_window_is_fullscreen(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr && window->fullscreen;
}

/**
 * resizable=false：把当前宽高锁成 min=max；true：清约束。
 * 与 request_size 的临时约束协作：size_locked 时 configure 后不清。
 */
extern "C" void composekn_window_set_resizable(ComposeKNWindow* window, bool resizable) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr) {
        return;
    }
    window->size_locked = !resizable;
    if (!resizable) {
        const int width = window->width > 0 ? window->width : 1;
        const int height = window->height > 0 ? window->height : 1;
        xdg_toplevel_set_min_size(window->toplevel, width, height);
        xdg_toplevel_set_max_size(window->toplevel, width, height);
    } else {
        xdg_toplevel_set_min_size(window->toplevel, 0, 0);
        xdg_toplevel_set_max_size(window->toplevel, 0, 0);
    }
    if (window->surface != nullptr) {
        wl_surface_commit(window->surface);
    }
    if (window->display != nullptr) {
        wl_display_flush(window->display);
    }
}

extern "C" void composekn_window_request_close(ComposeKNWindow* window) {
    window = resolve_window(window);
    mark_close_requested(window);
}

extern "C" bool composekn_window_is_close_requested(ComposeKNWindow* window) {
    window = resolve_window(window);
    return window != nullptr && window->close_requested;
}

extern "C" bool composekn_window_consume_close_requested(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr || !window->close_event_pending) {
        return false;
    }
    window->close_event_pending = false;
    return true;
}

extern "C" void composekn_window_set_title(ComposeKNWindow* window, const char* title) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr) {
        return;
    }
    xdg_toplevel_set_title(window->toplevel, title != nullptr ? title : "");
}

extern "C" void composekn_window_request_size(ComposeKNWindow* window, int w, int h) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr || window->shell_surface == nullptr) {
        return;
    }
    const int width = sanitize_dimension(w);
    const int height = sanitize_dimension(h);
    if (width <= 0 || height <= 0) {
        return;
    }
    window->width = width;
    window->height = height;
    // 用 min=max 提示 compositor 采用该尺寸（Wayland 无 SetWindowPos 等价物）。
    xdg_toplevel_set_min_size(window->toplevel, width, height);
    xdg_toplevel_set_max_size(window->toplevel, width, height);
    xdg_surface_set_window_geometry(window->shell_surface, 0, 0, width, height);
    window->clear_size_constraints_after_configure = true;
    if (window->egl_window != nullptr) {
        resize_egl_window(window);
    } else {
        window->resized = true;
    }
    if (window->surface != nullptr) {
        wl_surface_commit(window->surface);
    }
    if (window->display != nullptr) {
        wl_display_flush(window->display);
    }
}

/**
 * xdg_toplevel_move / _resize hand the pointer grab to the compositor and return
 * immediately. The button-up that ends the interactive gesture often never
 * reaches wl_pointer.button — Compose keeps primaryPressed stuck (same class of
 * bug as Win32 beginMove / DoDragDrop). Synthesize a left release if we still
 * think BTN_LEFT is down.
 */
static void synth_left_button_up_if_pressed(ComposeKNWindow* window, const char* reason) {
    if (window == nullptr) return;
    // linux/input-event-codes.h BTN_LEFT = 0x110 = 272; bit index = button - 272.
    constexpr uint32_t kBtnLeft = 272u;
    constexpr uint32_t kLeftBit = 1u << 0;
    if ((window->pointer_buttons & kLeftBit) == 0) return;
    window->pointer_buttons &= ~kLeftBit;
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_POINTER_BUTTON;
    event.x = static_cast<float>(window->pointer_x);
    event.y = static_cast<float>(window->pointer_y);
    event.button = kBtnLeft;
    event.state = WL_POINTER_BUTTON_STATE_RELEASED;
    event.modifiers = window->modifiers;
    push_event(window, event);
    std::fprintf(
        stderr,
        "composekn: pointer UP(synth after %s) pos=%.1f,%.1f\n",
        reason != nullptr ? reason : "?",
        window->pointer_x,
        window->pointer_y);
}

extern "C" void composekn_window_begin_move(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr || window->seat == nullptr) {
        return;
    }
    xdg_toplevel_move(window->toplevel, window->seat, window->last_serial);
    synth_left_button_up_if_pressed(window, "beginMove");
}

extern "C" void composekn_window_synth_left_up_if_pressed(
    ComposeKNWindow* window, const char* reason
) {
    window = resolve_window(window);
    if (window == nullptr) {
        window = g_shared.pointer_focus;
    }
    if (window == nullptr) {
        window = clipboard_target_window();
    }
    synth_left_button_up_if_pressed(window, reason);
}

extern "C" void composekn_window_set_parent(ComposeKNWindow* child, ComposeKNWindow* parent) {
    child = resolve_window(child);
    parent = parent != nullptr ? resolve_window(parent) : nullptr;
    if (child == nullptr || child->toplevel == nullptr) {
        return;
    }
    if (parent != nullptr && parent->toplevel == nullptr) {
        parent = nullptr;
    }
    xdg_toplevel_set_parent(
        child->toplevel,
        parent != nullptr ? parent->toplevel : nullptr);
    if (child->surface != nullptr) {
        wl_surface_commit(child->surface);
    }
    if (child->display != nullptr) {
        wl_display_flush(child->display);
    }
    std::fprintf(
        stderr,
        "composekn: xdg_toplevel_set_parent child=%p parent=%p\n",
        static_cast<void*>(child),
        static_cast<void*>(parent));
}

extern "C" bool composekn_window_set_always_on_top(ComposeKNWindow* window, bool on_top) {
    (void)window;
    (void)on_top;
    // xdg-shell 无 always-on-top；layer-shell / 厂商扩展另议。
    return false;
}

extern "C" bool composekn_window_always_on_top_supported(void) {
    return false;
}

extern "C" void composekn_window_begin_resize(ComposeKNWindow* window, uint32_t edges) {
    {
        FILE* f = fopen("/tmp/composekn_debug.log", "a");
        if (f) {
            std::fprintf(f, "begin_resize called edges=%u toplevel=%p seat=%p\n", edges,
                         window ? window->toplevel : nullptr, window ? window->seat : nullptr);
            std::fclose(f);
        }
    }
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr || window->seat == nullptr) {
        return;
    }
    xdg_toplevel_resize(window->toplevel, window->seat, window->last_serial, edges);
    synth_left_button_up_if_pressed(window, "beginResize");
}

/* ---- IME (zwp_text_input_v3) ---- */

extern "C" bool composekn_window_pop_ime_event(
    ComposeKNWindow* window,
    int32_t* kind,
    char* text,
    size_t text_size,
    uint32_t* a,
    uint32_t* b) {
    window = resolve_window(window);
    if (window == nullptr || window->ime_events.empty()) {
        return false;
    }
    ComposeKNImeEvent ev = window->ime_events.front();
    window->ime_events.pop_front();
    if (kind != nullptr) *kind = ev.kind;
    if (text != nullptr && text_size > 0) {
        const size_t n = ev.text.size();
        const size_t copy = (n < text_size - 1) ? n : (text_size - 1);
        if (copy > 0) {
            memcpy(text, ev.text.data(), copy);
        }
        text[copy] = '\0';
    }
    if (a != nullptr) *a = ev.a;
    if (b != nullptr) *b = ev.b;
    return true;
}

extern "C" void composekn_text_input_set_enabled(ComposeKNWindow* window, bool enabled) {
    window = resolve_window(window);
    if (window == nullptr) {
        return;
    }
    ensure_text_input();
    alias_shared_onto_window(window);
    if (window->text_input == nullptr) {
        return;
    }
    if (enabled) {
        zwp_text_input_v3_enable(window->text_input);
        zwp_text_input_v3_set_content_type(window->text_input, 0 /* hint none */, 1 /* purpose normal */);
        zwp_text_input_v3_commit(window->text_input);
        window->text_input_enabled = true;
    } else if (window->text_input_enabled) {
        zwp_text_input_v3_disable(window->text_input);
        zwp_text_input_v3_commit(window->text_input);
        window->text_input_enabled = false;
    }
    wl_display_flush(window->display);
}

extern "C" void composekn_text_input_set_cursor_rectangle(
    ComposeKNWindow* window, int x, int y, int width, int height) {
    window = resolve_window(window);
    if (window == nullptr || window->text_input == nullptr) {
        return;
    }
    zwp_text_input_v3_set_cursor_rectangle(window->text_input, x, y, width, height);
    wl_display_flush(window->display);
}

extern "C" void composekn_text_input_set_surrounding_text(
    ComposeKNWindow* window, const char* text, int cursor, int anchor) {
    window = resolve_window(window);
    if (window == nullptr || window->text_input == nullptr) {
        return;
    }
    zwp_text_input_v3_set_surrounding_text(
        window->text_input, (text != nullptr) ? text : "", cursor, anchor);
    wl_display_flush(window->display);
}

extern "C" void composekn_text_input_set_content_type(ComposeKNWindow* window, int hint, int purpose) {
    window = resolve_window(window);
    if (window == nullptr || window->text_input == nullptr) {
        return;
    }
    zwp_text_input_v3_set_content_type(window->text_input, hint, purpose);
    wl_display_flush(window->display);
}
