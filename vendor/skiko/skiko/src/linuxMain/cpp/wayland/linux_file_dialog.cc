/**
 * ComposeKN Linux：xdg-desktop-portal FileChooser（libdbus）。
 *
 * 对齐 Win32 两段式缓冲约定；parent_window 暂空（无 modality 绑定）。
 */

#include "wayland_bridge.h"

#include <dbus/dbus.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

DBusConnection* sessionBus() {
    DBusError err;
    dbus_error_init(&err);
    DBusConnection* c = dbus_bus_get(DBUS_BUS_SESSION, &err);
    if (dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        return nullptr;
    }
    return c;
}

bool nameHasOwner(DBusConnection* c, const char* name) {
    if (!c) return false;
    DBusError err;
    dbus_error_init(&err);
    DBusMessage* msg = dbus_message_new_method_call(
        "org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        "org.freedesktop.DBus",
        "NameHasOwner");
    if (!msg) return false;
    dbus_message_append_args(msg, DBUS_TYPE_STRING, &name, DBUS_TYPE_INVALID);
    DBusMessage* reply = dbus_connection_send_with_reply_and_block(c, msg, 2000, &err);
    dbus_message_unref(msg);
    if (!reply) {
        if (dbus_error_is_set(&err)) dbus_error_free(&err);
        return false;
    }
    dbus_bool_t has = FALSE;
    dbus_message_get_args(reply, &err, DBUS_TYPE_BOOLEAN, &has, DBUS_TYPE_INVALID);
    dbus_message_unref(reply);
    if (dbus_error_is_set(&err)) {
        dbus_error_free(&err);
        return false;
    }
    return has != FALSE;
}

/** filterUtf8 "Desc\n*.png;*.jpg\nAll\n*.*\n" → portal filters. */
void appendFilters(DBusMessageIter* opts, const char* filterUtf8) {
    if (!filterUtf8 || !filterUtf8[0]) return;
    DBusMessageIter entry, variant, arr, structIt, patterns, pattern;
    dbus_message_iter_open_container(opts, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
    const char* key = "filters";
    dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
    dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "a(sa(us))", &variant);
    dbus_message_iter_open_container(&variant, DBUS_TYPE_ARRAY, "(sa(us))", &arr);

    const char* p = filterUtf8;
    while (*p) {
        const char* descStart = p;
        while (*p && *p != '\n') ++p;
        std::string desc(descStart, p);
        if (*p == '\n') ++p;
        if (desc.empty()) break;
        const char* patStart = p;
        while (*p && *p != '\n') ++p;
        std::string pats(patStart, p);
        if (*p == '\n') ++p;

        dbus_message_iter_open_container(&arr, DBUS_TYPE_STRUCT, nullptr, &structIt);
        const char* dptr = desc.c_str();
        dbus_message_iter_append_basic(&structIt, DBUS_TYPE_STRING, &dptr);
        dbus_message_iter_open_container(&structIt, DBUS_TYPE_ARRAY, "(us)", &patterns);
        size_t i = 0;
        while (i < pats.size()) {
            size_t j = pats.find(';', i);
            if (j == std::string::npos) j = pats.size();
            std::string one = pats.substr(i, j - i);
            if (!one.empty()) {
                dbus_message_iter_open_container(&patterns, DBUS_TYPE_STRUCT, nullptr, &pattern);
                const uint32_t glob = 0; // 0 = glob
                const char* optr = one.c_str();
                dbus_message_iter_append_basic(&pattern, DBUS_TYPE_UINT32, &glob);
                dbus_message_iter_append_basic(&pattern, DBUS_TYPE_STRING, &optr);
                dbus_message_iter_close_container(&patterns, &pattern);
            }
            i = j + (j < pats.size() ? 1 : 0);
        }
        dbus_message_iter_close_container(&structIt, &patterns);
        dbus_message_iter_close_container(&arr, &structIt);
    }

    dbus_message_iter_close_container(&variant, &arr);
    dbus_message_iter_close_container(&entry, &variant);
    dbus_message_iter_close_container(opts, &entry);
}

std::string uriToPath(const char* uri) {
    if (!uri) return {};
    if (std::strncmp(uri, "file://", 7) == 0) {
        // 粗略：未做百分号解码（常见路径够用）。
        return std::string(uri + 7);
    }
    return std::string(uri);
}

std::string g_pendingResult;
bool g_havePending = false;

} // namespace

extern "C" bool composekn_linux_file_dialog_available(void) {
    DBusConnection* c = sessionBus();
    if (!c) return false;
    return nameHasOwner(c, "org.freedesktop.portal.Desktop");
}

extern "C" int32_t composekn_linux_file_dialog(
    int32_t mode,
    const char* title,
    const char* initialDir,
    const char* initialName,
    bool allowMultiple,
    const char* filterUtf8,
    char* buffer,
    int32_t bufferSize) {
    if (g_havePending) {
        const int32_t need = static_cast<int32_t>(g_pendingResult.size());
        if (buffer == nullptr || bufferSize <= need) return need > 0 ? need : 0;
        std::memcpy(buffer, g_pendingResult.data(), static_cast<size_t>(need));
        buffer[need] = '\0';
        g_havePending = false;
        g_pendingResult.clear();
        return need;
    }

    DBusConnection* c = sessionBus();
    if (!c) return -1;

    const char* method = (mode == 0) ? "OpenFile" : "SaveFile";
    DBusMessage* msg = dbus_message_new_method_call(
        "org.freedesktop.portal.Desktop",
        "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.FileChooser",
        method);
    if (!msg) return -1;

    DBusMessageIter args;
    dbus_message_iter_init_append(msg, &args);
    const char* parent = "";
    const char* titleStr = title ? title : "";
    dbus_message_iter_append_basic(&args, DBUS_TYPE_STRING, &parent);
    dbus_message_iter_append_basic(&args, DBUS_TYPE_STRING, &titleStr);

    DBusMessageIter opts;
    dbus_message_iter_open_container(&args, DBUS_TYPE_ARRAY, "{sv}", &opts);

    if (mode == 0 && allowMultiple) {
        DBusMessageIter entry, variant;
        dbus_message_iter_open_container(&opts, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
        const char* key = "multiple";
        dbus_bool_t v = TRUE;
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "b", &variant);
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_BOOLEAN, &v);
        dbus_message_iter_close_container(&entry, &variant);
        dbus_message_iter_close_container(&opts, &entry);
    }
    if (initialName && initialName[0] && mode != 0) {
        DBusMessageIter entry, variant;
        dbus_message_iter_open_container(&opts, DBUS_TYPE_DICT_ENTRY, nullptr, &entry);
        const char* key = "current_name";
        dbus_message_iter_append_basic(&entry, DBUS_TYPE_STRING, &key);
        dbus_message_iter_open_container(&entry, DBUS_TYPE_VARIANT, "s", &variant);
        dbus_message_iter_append_basic(&variant, DBUS_TYPE_STRING, &initialName);
        dbus_message_iter_close_container(&entry, &variant);
        dbus_message_iter_close_container(&opts, &entry);
    }
    if (initialDir && initialDir[0]) {
        // current_folder 是 ay（byte array path）；简化：用 current_folder 字符串变体不标准，
        // 多数 portal 也接受；这里跳过目录以降低复杂度（仍可用 filters）。
        (void)initialDir;
    }
    appendFilters(&opts, filterUtf8);
    dbus_message_iter_close_container(&args, &opts);

    DBusError err;
    dbus_error_init(&err);
    DBusMessage* reply = dbus_connection_send_with_reply_and_block(c, msg, 5000, &err);
    dbus_message_unref(msg);
    if (!reply) {
        if (dbus_error_is_set(&err)) dbus_error_free(&err);
        return -1;
    }

    const char* requestPath = nullptr;
    if (!dbus_message_get_args(
            reply, &err, DBUS_TYPE_OBJECT_PATH, &requestPath, DBUS_TYPE_INVALID) ||
        !requestPath) {
        dbus_message_unref(reply);
        if (dbus_error_is_set(&err)) dbus_error_free(&err);
        return -1;
    }
    std::string reqPath(requestPath);
    dbus_message_unref(reply);

    // Match Response signal on the request object.
    std::string match = "type='signal',interface='org.freedesktop.portal.Request',"
                        "member='Response',path='" +
        reqPath + "'";
    dbus_bus_add_match(c, match.c_str(), &err);
    if (dbus_error_is_set(&err)) dbus_error_free(&err);
    dbus_connection_flush(c);

    uint32_t responseCode = 1; // 1 = cancelled
    std::vector<std::string> paths;
    const int timeoutMs = 120000;
    const int slice = 50;
    int waited = 0;
    while (waited < timeoutMs) {
        dbus_connection_read_write(c, slice);
        DBusMessage* sig = nullptr;
        while ((sig = dbus_connection_pop_message(c)) != nullptr) {
            if (dbus_message_is_signal(sig, "org.freedesktop.portal.Request", "Response") &&
                dbus_message_get_path(sig) &&
                reqPath == dbus_message_get_path(sig)) {
                DBusMessageIter it;
                dbus_message_iter_init(sig, &it);
                if (dbus_message_iter_get_arg_type(&it) == DBUS_TYPE_UINT32) {
                    dbus_message_iter_get_basic(&it, &responseCode);
                    dbus_message_iter_next(&it);
                }
                if (responseCode == 0 &&
                    dbus_message_iter_get_arg_type(&it) == DBUS_TYPE_ARRAY) {
                    DBusMessageIter dict;
                    dbus_message_iter_recurse(&it, &dict);
                    while (dbus_message_iter_get_arg_type(&dict) == DBUS_TYPE_DICT_ENTRY) {
                        DBusMessageIter entry;
                        dbus_message_iter_recurse(&dict, &entry);
                        const char* key = nullptr;
                        if (dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_STRING) {
                            dbus_message_iter_get_basic(&entry, &key);
                            dbus_message_iter_next(&entry);
                        }
                        if (key && std::strcmp(key, "uris") == 0 &&
                            dbus_message_iter_get_arg_type(&entry) == DBUS_TYPE_VARIANT) {
                            DBusMessageIter var, arr;
                            dbus_message_iter_recurse(&entry, &var);
                            if (dbus_message_iter_get_arg_type(&var) == DBUS_TYPE_ARRAY) {
                                dbus_message_iter_recurse(&var, &arr);
                                while (dbus_message_iter_get_arg_type(&arr) == DBUS_TYPE_STRING) {
                                    const char* uri = nullptr;
                                    dbus_message_iter_get_basic(&arr, &uri);
                                    paths.push_back(uriToPath(uri));
                                    dbus_message_iter_next(&arr);
                                }
                            }
                        }
                        dbus_message_iter_next(&dict);
                    }
                }
                dbus_message_unref(sig);
                goto done;
            }
            dbus_message_unref(sig);
        }
        waited += slice;
    }
done:
    dbus_bus_remove_match(c, match.c_str(), nullptr);

    if (responseCode != 0 || paths.empty()) return 0;

    std::string out;
    for (size_t i = 0; i < paths.size(); ++i) {
        if (i) out.push_back('\n');
        out += paths[i];
    }
    const int32_t need = static_cast<int32_t>(out.size());
    if (buffer == nullptr || bufferSize <= need) {
        g_pendingResult = std::move(out);
        g_havePending = true;
        return need;
    }
    std::memcpy(buffer, out.data(), static_cast<size_t>(need));
    buffer[need] = '\0';
    return need;
}
