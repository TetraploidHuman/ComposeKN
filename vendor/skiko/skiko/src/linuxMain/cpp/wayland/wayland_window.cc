#include "wayland_bridge.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <wayland-client.h>
#include <wayland-egl.h>

#include <sys/mman.h>
#include <poll.h>
#include <errno.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <string>
#include <utility>
#include <vector>

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
    bool close_requested = false;
    bool resized = false;
    bool maximized = false;
    uint32_t configure_serial = 0;

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
    bool in_dispatch = false;

    std::deque<ComposeKNEvent> events;

    wl_data_device_manager* data_device_manager = nullptr;
    wl_data_device* data_device = nullptr;
    wl_data_source* clipboard_source = nullptr;
    wl_data_offer* selection_offer = nullptr;
    std::string clipboard_cache;
    std::string clipboard_set_pending;
    bool selection_has_text = false;
    bool selection_read_pending = false;
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

static ComposeKNWindow* resolve_window(ComposeKNWindow* window) {
    return g_primary_window != nullptr ? g_primary_window : window;
}

static void ensure_data_device(ComposeKNWindow* window);
static void read_selection_into_cache(ComposeKNWindow* window);
static bool init_egl(ComposeKNWindow* window);
static void try_init_egl_if_needed(ComposeKNWindow* window);
static void composekn_flush_deferred_frame(ComposeKNWindow* window);
static void ensure_text_input(ComposeKNWindow* window);

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
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (strcmp(interface, wl_compositor_interface.name) == 0) {
        window->compositor = static_cast<wl_compositor*>(
            wl_registry_bind(registry, id, &wl_compositor_interface, 4)
        );
    } else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        const uint32_t bind_version = version < 4 ? version : 4;
        window->wm_base = static_cast<xdg_wm_base*>(
            wl_registry_bind(registry, id, &xdg_wm_base_interface, bind_version)
        );
    } else if (strcmp(interface, wl_seat_interface.name) == 0) {
        const uint32_t bind_version = version < 7 ? version : 7;
        window->seat = static_cast<wl_seat*>(
            wl_registry_bind(registry, id, &wl_seat_interface, bind_version)
        );
    } else if (strcmp(interface, wp_fractional_scale_manager_v1_interface.name) == 0) {
        // Fractional scale disabled until EGL buffer sizing is fully validated.
        (void)version;
        (void)id;
        (void)registry;
    } else if (strcmp(interface, wl_data_device_manager_interface.name) == 0) {
        window->data_device_manager = static_cast<wl_data_device_manager*>(
            wl_registry_bind(registry, id, &wl_data_device_manager_interface, 3)
        );
        ensure_data_device(window);
    } else if (strcmp(interface, zxdg_decoration_manager_v1_interface.name) == 0) {
        const uint32_t bind_version = version < 2 ? version : 2;
        window->decoration_manager = static_cast<zxdg_decoration_manager_v1*>(
            wl_registry_bind(registry, id, &zxdg_decoration_manager_v1_interface, bind_version)
        );
    } else if (strcmp(interface, zwp_text_input_manager_v3_interface.name) == 0) {
        const uint32_t bind_version = version < 2 ? version : 2;
        window->text_input_manager = static_cast<zwp_text_input_manager_v3*>(
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
        for (size_t i = 0; i < count; ++i) {
            if (state_data[i] == XDG_TOPLEVEL_STATE_MAXIMIZED) {
                window->maximized = true;
                break;
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
    try_init_egl_if_needed(window);
    composekn_flush_deferred_frame(window);
}

static void xdg_toplevel_close(void* data, xdg_toplevel* toplevel) {
    (void)toplevel;
    auto* window = static_cast<ComposeKNWindow*>(data);
    window->close_requested = true;
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
    (void)pointer;
    (void)surface;
    auto* window = static_cast<ComposeKNWindow*>(data);
    {
        const char* msg = "composekn: pointer_enter callback fired\n";
        write(2, msg, 42);
    }
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
    (void)pointer;
    (void)serial;
    (void)surface;
    auto* window = static_cast<ComposeKNWindow*>(data);
    window->pointer_inside = false;
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_POINTER_LEAVE;
    push_event(window, event);
}

static void pointer_motion(
    void* data,
    wl_pointer* pointer,
    uint32_t time,
    wl_fixed_t surface_x,
    wl_fixed_t surface_y
) {
    (void)pointer;
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    (void)pointer;
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    (void)pointer;
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    (void)touch;
    (void)time;
    (void)surface;
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    ComposeKNEvent event{};
    event.type = COMPOSEKN_EVENT_TOUCH_DOWN;
    event.x = static_cast<float>(pos.first);
    event.y = static_cast<float>(pos.second);
    event.button = static_cast<uint32_t>(id);
    push_event(window, event);
}

static void touch_up(void* data, wl_touch* touch, uint32_t serial, uint32_t time, int32_t id) {
    (void)touch;
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
    uint32_t tid = static_cast<uint32_t>(id);
    double px = 0.0;
    double py = 0.0;
    auto it = window->touch_points.find(tid);
    if (it != window->touch_points.end()) {
        px = it->second.first;
        py = it->second.second;
    }
    window->touch_points.erase(tid);
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
    (void)touch;
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    (void)touch;
    auto* window = static_cast<ComposeKNWindow*>(data);
    window->touch_points.clear();
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

static void keyboard_keymap(
    void* data,
    wl_keyboard* keyboard,
    uint32_t format,
    int32_t fd,
    uint32_t size
) {
    (void)keyboard;
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    (void)serial;
    (void)surface;
    (void)keys;
}

static void keyboard_leave(void* data, wl_keyboard* keyboard, uint32_t serial, wl_surface* surface) {
    (void)data;
    (void)keyboard;
    (void)serial;
    (void)surface;
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
    (void)keyboard;
    (void)serial;
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    (void)keyboard;
    auto* window = static_cast<ComposeKNWindow*>(data);
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

static void data_offer_offer(void* data, wl_data_offer* offer, const char* mime_type) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    (void)offer;
    if (mime_type != nullptr && strstr(mime_type, "text/plain") != nullptr) {
        window->selection_has_text = true;
    }
}

static const wl_data_offer_listener data_offer_listener = {
    data_offer_offer,
};

static void read_selection_into_cache(ComposeKNWindow* window) {
    if (window == nullptr || window->selection_offer == nullptr || !window->selection_has_text) {
        return;
    }
    int fds[2];
    if (pipe(fds) != 0) {
        return;
    }
    wl_data_offer_receive(window->selection_offer, "text/plain;charset=utf-8", fds[1]);
    wl_display_flush(window->display);
    close(fds[1]);

    struct pollfd pfd{};
    pfd.fd = fds[0];
    pfd.events = POLLIN;
    if (poll(&pfd, 1, 1000) <= 0) {
        close(fds[0]);
        return;
    }

    std::string result;
    char buf[4096];
    ssize_t bytes_read;
    while ((bytes_read = read(fds[0], buf, sizeof(buf))) > 0) {
        result.append(buf, static_cast<size_t>(bytes_read));
    }
    close(fds[0]);

    if (result.empty()) {
        if (pipe(fds) != 0) {
            return;
        }
        wl_data_offer_receive(window->selection_offer, "text/plain", fds[1]);
        wl_display_flush(window->display);
        close(fds[1]);
        pfd.fd = fds[0];
        if (poll(&pfd, 1, 1000) > 0) {
            while ((bytes_read = read(fds[0], buf, sizeof(buf))) > 0) {
                result.append(buf, static_cast<size_t>(bytes_read));
            }
        }
        close(fds[0]);
    }
    window->clipboard_cache = result;
}

static void composekn_process_deferred_selection(ComposeKNWindow* window) {
    if (window == nullptr || !window->selection_read_pending) {
        return;
    }
    window->selection_read_pending = false;
    if (window->selection_has_text) {
        read_selection_into_cache(window);
    }
}

static void data_device_data_offer(void* data, wl_data_device* data_device, wl_data_offer* id) {
    (void)data;
    (void)data_device;
    (void)id;
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
    (void)serial;
    (void)surface;
    (void)x;
    (void)y;
    (void)id;
}

static void data_device_leave(void* data, wl_data_device* data_device) {
    (void)data;
    (void)data_device;
}

static void data_device_motion(void* data, wl_data_device* data_device, uint32_t time, wl_fixed_t x, wl_fixed_t y) {
    (void)data;
    (void)data_device;
    (void)time;
    (void)x;
    (void)y;
}

static void data_device_drop(void* data, wl_data_device* data_device) {
    (void)data;
    (void)data_device;
}

static void data_device_selection(
    void* data,
    wl_data_device* data_device,
    wl_data_offer* offer
) {
    (void)data_device;
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window->selection_offer != nullptr && window->selection_offer != offer) {
        wl_data_offer_destroy(window->selection_offer);
    }
    window->selection_offer = offer;
    window->selection_has_text = false;
    window->selection_read_pending = false;
    if (offer != nullptr) {
        wl_data_offer_add_listener(offer, &data_offer_listener, window);
        window->selection_read_pending = true;
    } else {
        window->clipboard_cache.clear();
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
    (void)mime_type;
    (void)source;
    auto* window = static_cast<ComposeKNWindow*>(data);
    const std::string& text = window->clipboard_set_pending;
    if (!text.empty()) {
        (void)write(fd, text.data(), text.size());
    }
    close(fd);
}

static void data_source_handle_cancelled(void* data, wl_data_source* source) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window->clipboard_source == source) {
        wl_data_source_destroy(source);
        window->clipboard_source = nullptr;
    }
}

static const wl_data_source_listener data_source_listener = {
    data_source_handle_target,
    data_source_handle_send,
    data_source_handle_cancelled,
};

static void ensure_data_device(ComposeKNWindow* window) {
    if (window == nullptr || window->data_device != nullptr) {
        return;
    }
    if (window->data_device_manager == nullptr || window->seat == nullptr) {
        return;
    }
    window->data_device = wl_data_device_manager_get_data_device(window->data_device_manager, window->seat);
    wl_data_device_add_listener(window->data_device, &data_device_listener, window);
}

/* ---- IME (zwp_text_input_v3) event handlers ---- */
static void ti_enter(void* data, zwp_text_input_v3*, wl_surface*) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_ENTER;
    push_ime_event(window, ev);
}
static void ti_leave(void* data, zwp_text_input_v3*, wl_surface*) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_LEAVE;
    push_ime_event(window, ev);
}
static void ti_preedit_string(void* data, zwp_text_input_v3*, const char* text, int32_t cursor_begin, int32_t cursor_end) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_PREEDIT;
    ev.text = (text != nullptr) ? text : "";
    ev.a = static_cast<uint32_t>(cursor_begin < 0 ? 0 : cursor_begin);
    ev.b = static_cast<uint32_t>(cursor_end < 0 ? 0 : cursor_end);
    push_ime_event(window, ev);
}
static void ti_commit_string(void* data, zwp_text_input_v3*, const char* text) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_COMMIT;
    ev.text = (text != nullptr) ? text : "";
    push_ime_event(window, ev);
}
static void ti_delete_surrounding_text(void* data, zwp_text_input_v3*, uint32_t before_length, uint32_t after_length) {
    auto* window = static_cast<ComposeKNWindow*>(data);
    ComposeKNImeEvent ev; ev.kind = COMPOSEKN_IME_DELETE;
    ev.a = before_length;
    ev.b = after_length;
    push_ime_event(window, ev);
}
static void ti_done(void* data, zwp_text_input_v3*, uint32_t) {
    auto* window = static_cast<ComposeKNWindow*>(data);
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

static void ensure_text_input(ComposeKNWindow* window) {
    if (window == nullptr || window->text_input != nullptr) {
        return;
    }
    if (window->text_input_manager == nullptr || window->seat == nullptr) {
        return;
    }
    window->text_input =
        zwp_text_input_manager_v3_get_text_input(window->text_input_manager, window->seat);
    zwp_text_input_v3_add_listener(window->text_input, &text_input_listener, window);
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
    auto* window = static_cast<ComposeKNWindow*>(data);
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
    if ((capabilities & WL_SEAT_CAPABILITY_POINTER) && window->pointer == nullptr) {
        window->pointer = wl_seat_get_pointer(seat);
        wl_pointer_add_listener(window->pointer, &pointer_listener, window);
    } else if (!(capabilities & WL_SEAT_CAPABILITY_POINTER) && window->pointer != nullptr) {
        wl_pointer_destroy(window->pointer);
        window->pointer = nullptr;
    }
    if ((capabilities & WL_SEAT_CAPABILITY_KEYBOARD) && window->keyboard == nullptr) {
        window->keyboard = wl_seat_get_keyboard(seat);
        wl_keyboard_add_listener(window->keyboard, &keyboard_listener, window);
    } else if (!(capabilities & WL_SEAT_CAPABILITY_KEYBOARD) && window->keyboard != nullptr) {
        wl_keyboard_destroy(window->keyboard);
        window->keyboard = nullptr;
    }
    if ((capabilities & WL_SEAT_CAPABILITY_TOUCH) && window->touch == nullptr) {
        window->touch = wl_seat_get_touch(seat);
        wl_touch_add_listener(window->touch, &touch_listener, window);
        fprintf(stderr, "composekn: wl_touch object created\n");
        fflush(stderr);
    } else if (!(capabilities & WL_SEAT_CAPABILITY_TOUCH) && window->touch != nullptr) {
        wl_touch_release(window->touch);
        window->touch = nullptr;
        window->touch_points.clear();
    }
    ensure_data_device(window);
    if (capabilities & WL_SEAT_CAPABILITY_KEYBOARD) {
        ensure_text_input(window);
    }
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

static void composekn_request_frame_internal(ComposeKNWindow* window);
static void composekn_process_deferred_selection(ComposeKNWindow* window);

static void frame_done(void* data, wl_callback* callback, uint32_t time) {
    (void)time;
    auto* window = static_cast<ComposeKNWindow*>(data);
    if (window->frame_callback == callback) {
        window->frame_callback = nullptr;
    }
    wl_callback_destroy(callback);
    window->frame_pending = false;
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
    window->egl_display = eglGetDisplay(static_cast<EGLNativeDisplayType>(window->display));
    if (window->egl_display == EGL_NO_DISPLAY) {
        std::fprintf(stderr, "composekn: eglGetDisplay failed\n");
        return false;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(window->egl_display, &major, &minor)) {
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
    int context_client_version = 3;
    if (eglChooseConfig(window->egl_display, config_attribs_es3, &window->egl_config, 1, &num_configs) && num_configs > 0) {
        context_client_version = 3;
    } else if (eglChooseConfig(window->egl_display, config_attribs_es2, &window->egl_config, 1, &num_configs) && num_configs > 0) {
        context_client_version = 2;
    } else {
        std::fprintf(stderr, "composekn: eglChooseConfig failed\n");
        return false;
    }

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

    const EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, context_client_version,
        EGL_NONE,
    };

    window->egl_context = eglCreateContext(
        window->egl_display,
        window->egl_config,
        EGL_NO_CONTEXT,
        context_attribs
    );
    if (window->egl_context == EGL_NO_CONTEXT) {
        std::fprintf(stderr, "composekn: eglCreateContext failed (ES %d)\n", context_client_version);
        return false;
    }

    return true;
}

static void try_init_egl_if_needed(ComposeKNWindow* window) {
    if (window == nullptr || window->egl_ready || !window->configured) {
        return;
    }
    if (window->width <= 0 || window->height <= 0) {
        return;
    }
    if (!init_egl(window)) {
        std::fprintf(stderr, "composekn: EGL init failed after configure\n");
        window->close_requested = true;
        return;
    }
    window->egl_ready = true;
    eglMakeCurrent(window->egl_display, window->egl_surface, window->egl_surface, window->egl_context);
    eglSwapInterval(window->egl_display, 1);
    std::fprintf(stderr, "composekn: EGL ready %dx%d\n", window->width, window->height);
    window->frame_requested = true;
}

extern "C" ComposeKNWindow* composekn_window_create(const char* title, int width, int height) {
    auto* window = new ComposeKNWindow();
    window->width = width > 0 ? width : 800;
    window->height = height > 0 ? height : 600;

    const char* display_name = std::getenv("WAYLAND_DISPLAY");
    window->display = wl_display_connect(display_name);
    if (window->display == nullptr) {
        std::fprintf(stderr, "composekn: wl_display_connect failed (WAYLAND_DISPLAY=%s)\n",
                     display_name ? display_name : "(unset)");
        composekn_window_destroy(window);
        return nullptr;
    }

    window->registry = wl_display_get_registry(window->display);
    wl_registry_add_listener(window->registry, &registry_listener, window);
    wl_display_roundtrip(window->display);

    if (window->compositor == nullptr || window->wm_base == nullptr) {
        std::fprintf(stderr, "composekn: missing compositor or xdg_wm_base\n");
        composekn_window_destroy(window);
        return nullptr;
    }

    xdg_wm_base_add_listener(window->wm_base, &xdg_wm_base_listener, window);

    if (window->seat != nullptr) {
        wl_seat_add_listener(window->seat, &seat_listener, window);
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

    wl_surface_commit(window->surface);
    wl_display_roundtrip(window->display);
    try_init_egl_if_needed(window);
    composekn_flush_deferred_frame(window);

    g_primary_window = window;
    return window;
}

extern "C" void composekn_window_destroy(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr) {
        return;
    }

    if (g_primary_window == window) {
        g_primary_window = nullptr;
    }
    if (window->clipboard_source != nullptr) {
        wl_data_source_destroy(window->clipboard_source);
        window->clipboard_source = nullptr;
    }
    if (window->selection_offer != nullptr) {
        wl_data_offer_destroy(window->selection_offer);
        window->selection_offer = nullptr;
    }
    if (window->data_device != nullptr) {
        wl_data_device_destroy(window->data_device);
        window->data_device = nullptr;
    }
    if (window->data_device_manager != nullptr) {
        wl_data_device_manager_destroy(window->data_device_manager);
        window->data_device_manager = nullptr;
    }

    if (window->frame_callback != nullptr) {
        wl_callback_destroy(window->frame_callback);
    }
    if (window->pointer != nullptr) {
        wl_pointer_destroy(window->pointer);
    }
    if (window->keyboard != nullptr) {
        wl_keyboard_destroy(window->keyboard);
    }
    if (window->touch != nullptr) {
        wl_touch_release(window->touch);
        window->touch = nullptr;
    }
    if (window->seat != nullptr) {
        wl_seat_destroy(window->seat);
    }
    if (window->fractional_scale != nullptr) {
        wp_fractional_scale_v1_destroy(window->fractional_scale);
    }
    if (window->fractional_scale_manager != nullptr) {
        wp_fractional_scale_manager_v1_destroy(window->fractional_scale_manager);
    }
    if (window->text_input != nullptr) {
        zwp_text_input_v3_destroy(window->text_input);
        window->text_input = nullptr;
    }
    if (window->text_input_manager != nullptr) {
        zwp_text_input_manager_v3_destroy(window->text_input_manager);
        window->text_input_manager = nullptr;
    }

    if (window->egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(window->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (window->egl_context != EGL_NO_CONTEXT) {
        eglDestroyContext(window->egl_display, window->egl_context);
    }
    if (window->egl_surface != EGL_NO_SURFACE) {
        eglDestroySurface(window->egl_display, window->egl_surface);
    }
    if (window->egl_window != nullptr) {
        wl_egl_window_destroy(window->egl_window);
    }
    if (window->egl_display != EGL_NO_DISPLAY) {
        eglTerminate(window->egl_display);
    }
    if (window->toplevel_decoration != nullptr) {
        zxdg_toplevel_decoration_v1_destroy(window->toplevel_decoration);
        window->toplevel_decoration = nullptr;
    }
    if (window->decoration_manager != nullptr) {
        zxdg_decoration_manager_v1_destroy(window->decoration_manager);
        window->decoration_manager = nullptr;
    }
    if (window->toplevel != nullptr) {
        xdg_toplevel_destroy(window->toplevel);
    }
    if (window->shell_surface != nullptr) {
        xdg_surface_destroy(window->shell_surface);
    }
    if (window->surface != nullptr) {
        wl_surface_destroy(window->surface);
    }
    if (window->wm_base != nullptr) {
        xdg_wm_base_destroy(window->wm_base);
    }
    if (window->registry != nullptr) {
        wl_registry_destroy(window->registry);
    }
    if (window->display != nullptr) {
        wl_display_disconnect(window->display);
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

    delete window;
}

extern "C" bool composekn_window_poll(ComposeKNWindow* window) {
    ComposeKNWindow* w = resolve_window(window);
    if (w == nullptr || w->close_requested) {
        return false;
    }

    w->in_dispatch = true;

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

    w->in_dispatch = false;
    composekn_flush_deferred_frame(w);
    composekn_process_deferred_selection(w);
    return !w->close_requested;
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
    if (!window->configured || !window->egl_ready || window->width <= 0 || window->height <= 0) {
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
    window->frame_callback = wl_surface_frame(window->surface);
    if (window->frame_callback == nullptr) {
        std::fprintf(stderr, "composekn: wl_surface_frame returned null\n");
        window->frame_pending = false;
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
    if (window == nullptr || window->egl_display == EGL_NO_DISPLAY) {
        return;
    }
    if (!window->egl_ready) {
        try_init_egl_if_needed(window);
    }
    if (window->egl_context == EGL_NO_CONTEXT) {
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
    if (g_primary_window == nullptr || buffer == nullptr || buffer_size == 0) {
        return false;
    }
    const std::string& text = g_primary_window->clipboard_cache;
    strncpy(buffer, text.c_str(), buffer_size - 1);
    buffer[buffer_size - 1] = '\0';
    return true;
}

extern "C" void composekn_clipboard_set_text(const char* text) {
    if (g_primary_window == nullptr || g_primary_window->data_device_manager == nullptr) {
        return;
    }
    auto* window = g_primary_window;
    window->clipboard_set_pending = text != nullptr ? text : "";
    window->clipboard_cache = window->clipboard_set_pending;
    if (window->clipboard_source != nullptr) {
        wl_data_source_destroy(window->clipboard_source);
        window->clipboard_source = nullptr;
    }
    window->clipboard_source = wl_data_device_manager_create_data_source(window->data_device_manager);
    wl_data_source_add_listener(window->clipboard_source, &data_source_listener, window);
    wl_data_source_offer(window->clipboard_source, "text/plain;charset=utf-8");
    wl_data_source_offer(window->clipboard_source, "text/plain");
    ensure_data_device(window);
    if (window->data_device != nullptr) {
        wl_data_device_set_selection(window->data_device, window->clipboard_source, window->last_serial);
    }
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

extern "C" void composekn_window_request_close(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr) {
        return;
    }
    window->close_requested = true;
}

extern "C" void composekn_window_begin_move(ComposeKNWindow* window) {
    window = resolve_window(window);
    if (window == nullptr || window->toplevel == nullptr || window->seat == nullptr) {
        return;
    }
    xdg_toplevel_move(window->toplevel, window->seat, window->last_serial);
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
    ensure_text_input(window);
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
