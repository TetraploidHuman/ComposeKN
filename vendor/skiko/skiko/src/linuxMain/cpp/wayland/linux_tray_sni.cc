/**
 * ComposeKN Linux StatusNotifierItem + 极简 DBusMenu（libdbus）。
 *
 * 菜单串格式（Kotlin → C）：每行 `id\\tkind\\tenabled\\tlabel`
 *   kind: I=item，S=separator；enabled: 0/1。
 */

#include "wayland_bridge.h"

#include <dbus/dbus.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

struct MenuEntry {
    int id = 0;
    bool separator = false;
    bool enabled = true;
    std::string label;
};

struct TrayState {
    DBusConnection* conn = nullptr;
    ComposeKNLinuxTrayCallback callback = nullptr;
    void* user = nullptr;
    std::string tooltip;
    std::string busName;
    std::vector<uint8_t> iconBgra;
    int iconW = 0;
    int iconH = 0;
    std::vector<MenuEntry> menu;
    bool registered = false;
};

TrayState g_tray;

void trayLog(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    std::fprintf(stderr, "[composekn-tray] %s\n", buf);
}

bool appendVariantString(DBusMessageIter* parent, const char* key, const char* value) {
    DBusMessageIter entry, variant;
    if (!dbus_message_iter_open_container(parent, DBUS_TYPE_DICT_ENTRY, nullptr, &entry))
        return false;
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    if (!dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &variant))
        return false;
    dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &value);
    dbus_message_iter_close_container(&entry, &variant);
    dbus_message_iter_close_container(parent, &entry);
    return true;
}

bool appendVariantObjectPath(DBusMessageIter* parent, const char* key, const char* path) {
    DBusMessageIter entry, variant;
    dbus_message_iter_open_container(parent, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "o", &variant);
    dbus_message_iter_append_basic(&variant, DBUS_TYPE_OBJECT_PATH, &path);
    dbus_message_iter_close_container(&entry, &variant);
    dbus_message_iter_close_container(parent, &entry);
    return true;
}

bool appendVariantInt(DBusMessageIter* parent, const char* key, int32_t value) {
    DBusMessageIter entry, variant;
    dbus_message_iter_open_container(parent, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "i", &variant);
    dbus_message_iter_append_basic(&variant, DBUS_TYPE_INT32, &value);
    dbus_message_iter_close_container(&entry, &variant);
    dbus_message_iter_close_container(parent, &entry);
    return true;
}

bool appendIconPixmap(DBusMessageIter* parent) {
    DBusMessageIter entry, variant, arr, stru, bytes;
    dbus_message_iter_open_container(parent, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
    const char* key = "IconPixmap";
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "a(iiay)", &variant);
    dbus_message_iter_open_container(&variant, DBUS_TYPE_ARRAY, "(iiay)", &arr);
    if (g_tray.iconW > 0 && g_tray.iconH > 0 && !g_tray.iconBgra.empty()) {
        // SNI IconPixmap 期望 ARGB 网络字节序；我们存的是 BGRA → 转 ARGB。
        std::vector<uint8_t> argb(g_tray.iconBgra.size());
        for (size_t i = 0; i + 3 < g_tray.iconBgra.size(); i += 4) {
            argb[i + 0] = g_tray.iconBgra[i + 3]; // A
            argb[i + 1] = g_tray.iconBgra[i + 2]; // R
            argb[i + 2] = g_tray.iconBgra[i + 1]; // G
            argb[i + 3] = g_tray.iconBgra[i + 0]; // B
        }
        dbus_message_iter_open_container(&arr, DBUS_TYPE_STRUCT, nullptr, &stru);
        int32_t w = g_tray.iconW;
        int32_t h = g_tray.iconH;
        dbus_message_iter_append_basic(&stru, DBUS_TYPE_INT32, &w);
        dbus_message_iter_append_basic(&stru, DBUS_TYPE_INT32, &h);
        dbus_message_iter_open_container(&stru, DBUS_TYPE_ARRAY, "y", &bytes);
        const uint8_t* p = argb.data();
        // dbus_message_iter_append_fixed_array
        int len = static_cast<int>(argb.size());
        dbus_message_iter_append_fixed_array(&bytes, DBUS_TYPE_BYTE, &p, len);
        dbus_message_iter_close_container(&stru, &bytes);
        dbus_message_iter_close_container(&arr, &stru);
    }
    dbus_message_iter_close_container(&variant, &arr);
    dbus_message_iter_close_container(&entry, &variant);
    dbus_message_iter_close_container(parent, &entry);
    return true;
}

void fillSniProperties(DBusMessageIter* arr) {
    appendVariantString(arr, "Category", "ApplicationStatus");
    appendVariantString(arr, "Id", "ComposeKN");
    appendVariantString(arr, "Title", g_tray.tooltip.empty() ? "ComposeKN" : g_tray.tooltip.c_str());
    appendVariantString(arr, "Status", "Active");
    appendVariantInt(arr, "WindowId", 0);
    appendVariantObjectPath(arr, "Menu", "/Menu");
    appendIconPixmap(arr);
    // ToolTip: (sa(iiay)ss) — 简化为空标题/描述
    {
        DBusMessageIter entry, variant, stru, icons;
        dbus_message_iter_open_container(arr, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
        const char* key = "ToolTip";
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "(sa(iiay)ss)", &variant);
        dbus_message_iter_open_container(&variant, DBUS_TYPE_STRUCT, nullptr, &stru);
        const char* iconName = "";
        dbus_message_iter_append_basic(&stru, DBUS_TYPE_STRING, &iconName);
        dbus_message_iter_open_container(&stru, DBUS_TYPE_ARRAY, "(iiay)", &icons);
        dbus_message_iter_close_container(&stru, &icons);
        const char* title = g_tray.tooltip.c_str();
        const char* body = "";
        dbus_message_iter_append_basic(&stru, DBUS_TYPE_STRING, &title);
        dbus_message_iter_append_basic(&stru, DBUS_TYPE_STRING, &body);
        dbus_message_iter_close_container(&variant, &stru);
        dbus_message_iter_close_container(&entry, &variant);
        dbus_message_iter_close_container(arr, &entry);
    }
}

DBusHandlerResult handleSni(DBusConnection*, DBusMessage* msg, void*) {
    const char* iface = dbus_message_get_interface(msg);
    const char* member = dbus_message_get_member(msg);
    if (!iface || !member) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;

    if (std::strcmp(iface, "org.freedesktop.DBus.Properties") == 0) {
        if (std::strcmp(member, "GetAll") == 0) {
            DBusMessage* reply = dbus_message_new_method_return(msg);
            DBusMessageIter args, arr;
            dbus_message_iter_init_append(reply, &args);
            dbus_message_iter_open_container(&args, DBUS_TYPE_ARRAY, "{sv}", &arr);
            fillSniProperties(&arr);
            dbus_message_iter_close_container(&args, &arr);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
        if (std::strcmp(member, "Get") == 0) {
            // 简化：回空字符串，面板多用 GetAll。
            DBusMessage* reply = dbus_message_new_method_return(msg);
            DBusMessageIter args, variant;
            dbus_message_iter_init_append(reply, &args);
            dbus_message_iter_open_container(&args, DBUS_TYPE_VARIANT, "s", &variant);
            const char* empty = "";
            dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &empty);
            dbus_message_iter_close_container(&args, &variant);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
    }

    if (std::strcmp(iface, "org.kde.StatusNotifierItem") == 0) {
        if (std::strcmp(member, "Activate") == 0 ||
            std::strcmp(member, "SecondaryActivate") == 0) {
            if (g_tray.callback) g_tray.callback(0, 0, g_tray.user);
            DBusMessage* reply = dbus_message_new_method_return(msg);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
        if (std::strcmp(member, "ContextMenu") == 0) {
            // 面板会弹 DBusMenu；这里无需自绘。
            DBusMessage* reply = dbus_message_new_method_return(msg);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
    }

    if (std::strcmp(iface, "org.freedesktop.DBus.Introspectable") == 0 &&
        std::strcmp(member, "Introspect") == 0) {
        const char* xml =
            "<!DOCTYPE node PUBLIC \"-//freedesktop//DTD D-BUS Object Introspection 1.0//EN\" "
            "\"http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd\">"
            "<node>"
            "<interface name=\"org.kde.StatusNotifierItem\">"
            "<method name=\"Activate\"><arg type=\"i\" name=\"x\" direction=\"in\"/>"
            "<arg type=\"i\" name=\"y\" direction=\"in\"/></method>"
            "<method name=\"ContextMenu\"><arg type=\"i\" name=\"x\" direction=\"in\"/>"
            "<arg type=\"i\" name=\"y\" direction=\"in\"/></method>"
            "<method name=\"SecondaryActivate\"><arg type=\"i\" name=\"x\" direction=\"in\"/>"
            "<arg type=\"i\" name=\"y\" direction=\"in\"/></method>"
            "<property name=\"Category\" type=\"s\" access=\"read\"/>"
            "<property name=\"Id\" type=\"s\" access=\"read\"/>"
            "<property name=\"Title\" type=\"s\" access=\"read\"/>"
            "<property name=\"Status\" type=\"s\" access=\"read\"/>"
            "<property name=\"WindowId\" type=\"i\" access=\"read\"/>"
            "<property name=\"IconPixmap\" type=\"a(iiay)\" access=\"read\"/>"
            "<property name=\"Menu\" type=\"o\" access=\"read\"/>"
            "<property name=\"ToolTip\" type=\"(sa(iiay)ss)\" access=\"read\"/>"
            "</interface>"
            "<interface name=\"org.freedesktop.DBus.Properties\">"
            "<method name=\"Get\"/>"
            "<method name=\"GetAll\"/>"
            "</interface>"
            "</node>";
        DBusMessage* reply = dbus_message_new_method_return(msg);
        dbus_message_append_args(reply, DBUS_TYPE_STRING, &xml, DBUS_TYPE_INVALID);
        dbus_connection_send(g_tray.conn, reply, nullptr);
        dbus_message_unref(reply);
        return DBUS_HANDLER_RESULT_HANDLED;
    }
    return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}

void appendMenuItemProps(DBusMessageIter* props, const MenuEntry& e) {
    if (e.separator) {
        appendVariantString(props, "type", "separator");
    } else {
        appendVariantString(props, "label", e.label.c_str());
        appendVariantString(props, "type", "standard");
        DBusMessageIter entry, variant;
        dbus_message_iter_open_container(props, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
        const char* key = "enabled";
        dbus_bool_t en = e.enabled ? TRUE : FALSE;
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "b", &variant);
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &en);
        dbus_message_iter_close_container(&entry, &variant);
        dbus_message_iter_close_container(props, &entry);
    }
}

DBusHandlerResult handleMenu(DBusConnection*, DBusMessage* msg, void*) {
    const char* iface = dbus_message_get_interface(msg);
    const char* member = dbus_message_get_member(msg);
    if (!iface || !member) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;

    if (std::strcmp(iface, "com.canonical.dbusmenu") == 0) {
        if (std::strcmp(member, "GetLayout") == 0) {
            DBusMessage* reply = dbus_message_new_method_return(msg);
            DBusMessageIter args, root, props, children, child, childProps, emptyKids;
            dbus_message_iter_init_append(reply, &args);
            uint32_t revision = 1;
            dbus_message_iter_append_basic(&args, DBUS_TYPE_UINT32, &revision);
            // root (id=0, props, children)
            dbus_message_iter_open_container(&args, DBUS_TYPE_STRUCT, nullptr, &root);
            int32_t rootId = 0;
            dbus_message_iter_append_basic(&root, DBUS_TYPE_INT32, &rootId);
            dbus_message_iter_open_container(&root, DBUS_TYPE_ARRAY, "{sv}", &props);
            dbus_message_iter_close_container(&root, &props);
            dbus_message_iter_open_container(&root, DBUS_TYPE_ARRAY, "(ia{sv}av)", &children);
            for (const auto& e : g_tray.menu) {
                dbus_message_iter_open_container(&children, DBUS_TYPE_STRUCT, nullptr, &child);
                int32_t id = e.id;
                dbus_message_iter_append_basic(&child, DBUS_TYPE_INT32, &id);
                dbus_message_iter_open_container(&child, DBUS_TYPE_ARRAY, "{sv}", &childProps);
                appendMenuItemProps(&childProps, e);
                dbus_message_iter_close_container(&child, &childProps);
                dbus_message_iter_open_container(&child, DBUS_TYPE_ARRAY, "v", &emptyKids);
                dbus_message_iter_close_container(&child, &emptyKids);
                dbus_message_iter_close_container(&children, &child);
            }
            dbus_message_iter_close_container(&root, &children);
            dbus_message_iter_close_container(&args, &root);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
        if (std::strcmp(member, "Event") == 0) {
            int32_t id = 0;
            const char* eventId = nullptr;
            DBusMessageIter it;
            dbus_message_iter_init(msg, &it);
            if (dbus_message_iter_get_arg_type(&it) == DBUS_TYPE_INT32) {
                dbus_message_iter_get_basic(&it, &id);
                dbus_message_iter_next(&it);
            }
            if (dbus_message_iter_get_arg_type(&it) == DBUS_TYPE_STRING) {
                dbus_message_iter_get_basic(&it, &eventId);
            }
            if (eventId && std::strcmp(eventId, "clicked") == 0 && g_tray.callback) {
                g_tray.callback(1, id, g_tray.user);
            }
            DBusMessage* reply = dbus_message_new_method_return(msg);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
        if (std::strcmp(member, "AboutToShow") == 0) {
            DBusMessage* reply = dbus_message_new_method_return(msg);
            dbus_bool_t needUpdate = FALSE;
            dbus_message_append_args(reply, DBUS_TYPE_BOOLEAN, &needUpdate, DBUS_TYPE_INVALID);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
        if (std::strcmp(member, "GetGroupProperties") == 0) {
            DBusMessage* reply = dbus_message_new_method_return(msg);
            DBusMessageIter args, arr;
            dbus_message_iter_init_append(reply, &args);
            dbus_message_iter_open_container(&args, DBUS_TYPE_ARRAY, "(ia{sv})", &arr);
            dbus_message_iter_close_container(&args, &arr);
            dbus_connection_send(g_tray.conn, reply, nullptr);
            dbus_message_unref(reply);
            return DBUS_HANDLER_RESULT_HANDLED;
        }
    }

    if (std::strcmp(iface, "org.freedesktop.DBus.Properties") == 0 &&
        std::strcmp(member, "Get") == 0) {
        // Version / Status
        DBusMessage* reply = dbus_message_new_method_return(msg);
        DBusMessageIter args, variant;
        dbus_message_iter_init_append(reply, &args);
        dbus_message_iter_open_container(&args, DBUS_TYPE_VARIANT, "u", &variant);
        uint32_t ver = 3;
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_UINT32, &ver);
        dbus_message_iter_close_container(&args, &variant);
        dbus_connection_send(g_tray.conn, reply, nullptr);
        dbus_message_unref(reply);
        return DBUS_HANDLER_RESULT_HANDLED;
    }

    if (std::strcmp(iface, "org.freedesktop.DBus.Introspectable") == 0 &&
        std::strcmp(member, "Introspect") == 0) {
        const char* xml =
            "<node><interface name=\"com.canonical.dbusmenu\">"
            "<method name=\"GetLayout\"/>"
            "<method name=\"GetGroupProperties\"/>"
            "<method name=\"Event\"/>"
            "<method name=\"AboutToShow\"/>"
            "<property name=\"Version\" type=\"u\" access=\"read\"/>"
            "<property name=\"Status\" type=\"s\" access=\"read\"/>"
            "</interface></node>";
        DBusMessage* reply = dbus_message_new_method_return(msg);
        dbus_message_append_args(reply, DBUS_TYPE_STRING, &xml, DBUS_TYPE_INVALID);
        dbus_connection_send(g_tray.conn, reply, nullptr);
        dbus_message_unref(reply);
        return DBUS_HANDLER_RESULT_HANDLED;
    }
    return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}

bool registerWithWatcher() {
    if (!g_tray.conn) return false;
    DBusError err;
    dbus_error_init(&err);
    // RegisterStatusNotifierItem(s service)
    DBusMessage* msg = dbus_message_new_method_call(
        "org.kde.StatusNotifierWatcher",
        "/StatusNotifierWatcher",
        "org.kde.StatusNotifierWatcher",
        "RegisterStatusNotifierItem");
    if (!msg) return false;
    const char* service = g_tray.busName.c_str();
    dbus_message_append_args(msg, DBUS_TYPE_STRING, &service, DBUS_TYPE_INVALID);
    DBusMessage* reply = dbus_connection_send_with_reply_and_block(g_tray.conn, msg, 3000, &err);
    dbus_message_unref(msg);
    if (!reply) {
        if (dbus_error_is_set(&err)) {
            trayLog("RegisterStatusNotifierItem failed: %s", err.message);
            dbus_error_free(&err);
        }
        return false;
    }
    dbus_message_unref(reply);
    return true;
}

void emitNewIcon() {
    if (!g_tray.conn) return;
    DBusMessage* sig = dbus_message_new_signal(
        "/StatusNotifierItem", "org.kde.StatusNotifierItem", "NewIcon");
    if (!sig) return;
    dbus_connection_send(g_tray.conn, sig, nullptr);
    dbus_message_unref(sig);
    dbus_connection_flush(g_tray.conn);
}

void emitLayoutUpdated() {
    if (!g_tray.conn) return;
    DBusMessage* sig = dbus_message_new_signal(
        "/Menu", "com.canonical.dbusmenu", "LayoutUpdated");
    if (!sig) return;
    uint32_t rev = 1;
    int32_t parent = 0;
    dbus_message_append_args(
        sig, DBUS_TYPE_UINT32, &rev, DBUS_TYPE_INT32, &parent, DBUS_TYPE_INVALID);
    dbus_connection_send(g_tray.conn, sig, nullptr);
    dbus_message_unref(sig);
    dbus_connection_flush(g_tray.conn);
}

} // namespace

extern "C" bool composekn_linux_tray_available(void) {
    DBusError err;
    dbus_error_init(&err);
    DBusConnection* c = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (c) {
        dbus_error_init(&err);
        DBusMessage* msg = dbus_message_new_method_call(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
            "NameHasOwner");
        const char* name = "org.kde.StatusNotifierWatcher";
        dbus_message_append_args(msg, DBUS_TYPE_STRING, &name, DBUS_TYPE_INVALID);
        DBusMessage* reply = dbus_connection_send_with_reply_and_block(c, msg, 1500, &err);
        dbus_message_unref(msg);
        if (reply) {
            dbus_bool_t has = FALSE;
            dbus_message_get_args(reply, nullptr, DBUS_TYPE_BOOLEAN, &has, DBUS_TYPE_INVALID);
            dbus_message_unref(reply);
            if (has) return true;
        }
        if (dbus_error_is_set(&err)) dbus_error_free(&err);
    } else if (dbus_error_is_set(&err)) {
        dbus_error_free(&err);
    }
    // 无 SNI watcher 时：能 notify-send 也算支持（通知通道）。
    if (access("/usr/bin/notify-send", F_OK) == 0 ||
        access("/bin/notify-send", F_OK) == 0 ||
        access("/run/current-system/sw/bin/notify-send", F_OK) == 0) {
        return true;
    }
    // Nix shell / 非标准前缀：扫 PATH。
    const char* path = getenv("PATH");
    if (path == nullptr) return false;
    std::string paths(path);
    size_t start = 0;
    while (start <= paths.size()) {
        size_t end = paths.find(':', start);
        if (end == std::string::npos) end = paths.size();
        std::string dir = paths.substr(start, end - start);
        if (!dir.empty()) {
            std::string candidate = dir + "/notify-send";
            if (access(candidate.c_str(), F_OK) == 0) return true;
        }
        if (end == paths.size()) break;
        start = end + 1;
    }
    return false;
}

extern "C" bool composekn_linux_tray_create(
    const char* tooltip_utf8, ComposeKNLinuxTrayCallback cb, void* user) {
    if (g_tray.registered) {
        g_tray.callback = cb;
        g_tray.user = user;
        if (tooltip_utf8) g_tray.tooltip = tooltip_utf8;
        return true;
    }
    DBusError err;
    dbus_error_init(&err);
    g_tray.conn = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (!g_tray.conn) {
        if (dbus_error_is_set(&err)) {
            trayLog("dbus_bus_get: %s", err.message);
            dbus_error_free(&err);
        }
        return false;
    }
    dbus_connection_set_exit_on_disconnect(g_tray.conn, FALSE);
    g_tray.callback = cb;
    g_tray.user = user;
    g_tray.tooltip = tooltip_utf8 ? tooltip_utf8 : "";
    const char* unique = dbus_bus_get_unique_name(g_tray.conn);
    g_tray.busName = unique ? unique : "";

    static const DBusObjectPathVTable sniVtable = {
        nullptr, handleSni, nullptr, nullptr, nullptr, nullptr};
    static const DBusObjectPathVTable menuVtable = {
        nullptr, handleMenu, nullptr, nullptr, nullptr, nullptr};
    if (!dbus_connection_register_object_path(
            g_tray.conn, "/StatusNotifierItem", &sniVtable, nullptr) ||
        !dbus_connection_register_object_path(
            g_tray.conn, "/Menu", &menuVtable, nullptr)) {
        trayLog("register_object_path failed");
        return false;
    }
    if (!registerWithWatcher()) {
        trayLog("watcher register failed — icon may not show");
        // 仍标记 created，方便 notify-send；部分环境无 watcher。
    }
    g_tray.registered = true;
    trayLog("SNI create ok bus=%s", g_tray.busName.c_str());
    return true;
}

extern "C" void composekn_linux_tray_set_tooltip(const char* tooltip_utf8) {
    g_tray.tooltip = tooltip_utf8 ? tooltip_utf8 : "";
    if (!g_tray.conn) return;
    DBusMessage* sig = dbus_message_new_signal(
        "/StatusNotifierItem", "org.kde.StatusNotifierItem", "NewTitle");
    if (sig) {
        dbus_connection_send(g_tray.conn, sig, nullptr);
        dbus_message_unref(sig);
        dbus_connection_flush(g_tray.conn);
    }
}

extern "C" void composekn_linux_tray_set_menu(const char* itemsUtf8) {
    g_tray.menu.clear();
    if (!itemsUtf8) {
        emitLayoutUpdated();
        return;
    }
    const char* p = itemsUtf8;
    while (*p) {
        const char* lineStart = p;
        while (*p && *p != '\n') ++p;
        std::string line(lineStart, p);
        if (*p == '\n') ++p;
        if (line.empty()) continue;
        // id \t kind \t enabled \t label
        MenuEntry e;
        size_t t1 = line.find('\t');
        size_t t2 = t1 == std::string::npos ? std::string::npos : line.find('\t', t1 + 1);
        size_t t3 = t2 == std::string::npos ? std::string::npos : line.find('\t', t2 + 1);
        if (t1 == std::string::npos || t2 == std::string::npos || t3 == std::string::npos) continue;
        e.id = std::atoi(line.substr(0, t1).c_str());
        char kind = line[t1 + 1];
        e.separator = (kind == 'S');
        e.enabled = line.substr(t2 + 1, t3 - t2 - 1) != "0";
        e.label = line.substr(t3 + 1);
        g_tray.menu.push_back(e);
    }
    emitLayoutUpdated();
}

extern "C" bool composekn_linux_tray_set_icon(int32_t w, int32_t h, const uint8_t* bgra) {
    if (!bgra || w <= 0 || h <= 0) return false;
    g_tray.iconW = w;
    g_tray.iconH = h;
    g_tray.iconBgra.assign(bgra, bgra + static_cast<size_t>(w) * static_cast<size_t>(h) * 4u);
    emitNewIcon();
    return true;
}

extern "C" void composekn_linux_tray_notify(
    const char* title_utf8, const char* body_utf8, int32_t type) {
    // 仍走 notify-send（portal Notifications 可后续替换）。
    const char* urgency = (type >= 2) ? "critical" : "normal";
    const char* icon = "dialog-information";
    if (type == 2) icon = "dialog-warning";
    if (type == 3) icon = "dialog-error";
    auto sh = [](const char* s) -> std::string {
        std::string o = "'";
        for (const char* p = s ? s : ""; *p; ++p) {
            if (*p == '\'') o += "'\"'\"'";
            else o += *p;
        }
        o += "'";
        return o;
    };
    std::string cmd = std::string("notify-send -u ") + urgency + " -i " + icon + " " +
        sh(title_utf8) + " " + sh(body_utf8) + " 2>/dev/null";
    int rc = std::system(cmd.c_str());
    (void)rc;
}

extern "C" void composekn_linux_tray_dispatch(void) {
    if (!g_tray.conn) return;
    dbus_connection_read_write_dispatch(g_tray.conn, 0);
}

extern "C" void composekn_linux_tray_destroy(void) {
    if (g_tray.conn) {
        dbus_connection_unregister_object_path(g_tray.conn, "/StatusNotifierItem");
        dbus_connection_unregister_object_path(g_tray.conn, "/Menu");
        // shared bus connection — unref only
        dbus_connection_unref(g_tray.conn);
        g_tray.conn = nullptr;
    }
    g_tray.registered = false;
    g_tray.callback = nullptr;
    g_tray.user = nullptr;
    g_tray.menu.clear();
    g_tray.iconBgra.clear();
    g_tray.iconW = g_tray.iconH = 0;
    g_tray.tooltip.clear();
    g_tray.busName.clear();
}
