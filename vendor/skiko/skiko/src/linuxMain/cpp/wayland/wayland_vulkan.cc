/**
 * ComposeKN Linux Wayland Graphite + Vulkan 桥。
 *
 * 对照 win32_vulkan.cc / 上游 GraphiteNativeVulkanWindowContext，但：
 *   - 不依赖 tools/（自建 VkInstance/Device + Wayland surface/swapchain）；
 *   - C API 暴露给 Kotlin（begin_frame → SkCanvas*，end_frame → present）；
 *   - 动态 dlopen("libvulkan.so.1")，不链 libvulkan；
 *   - 不用 std::unordered_map（konan 链 __throw_bad_array_new_length）；
 *   - process-wide 共享 VkInstance/Device/Graphite Context（refcount）；
 *     每窗只持有 surface/swapchain/recorder/blit 等。
 *
 * 未定义 SK_VULKAN+SK_GRAPHITE 时全部 stub 失败（链纯 GL Skia 包时仍能编过）。
 */
#include "wayland_bridge.h"

#include <dlfcn.h>

#include <algorithm>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#if defined(SK_VULKAN) && defined(SK_GRAPHITE)

#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkSurface.h"
#include "include/core/SkSurfaceProps.h"
#include "include/gpu/MutableTextureState.h"
#include "include/gpu/graphite/BackendSemaphore.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Recorder.h"
#include "include/gpu/graphite/Recording.h"
#include "include/gpu/graphite/Surface.h"
#include "include/gpu/graphite/vk/VulkanGraphiteContext.h"
#include "include/gpu/graphite/vk/VulkanGraphiteTypes.h"
#include "include/gpu/vk/VulkanBackendContext.h"
#include "include/gpu/vk/VulkanExtensions.h"
#include "include/gpu/vk/VulkanMemoryAllocator.h"
#include "include/gpu/vk/VulkanMutableTextureState.h"
#include "include/gpu/vk/VulkanPreferredFeatures.h"
#include "include/gpu/vk/VulkanTypes.h"
#include "include/private/gpu/vk/SkiaVulkan.h"

#include <vulkan/vulkan_wayland.h>

// VulkanMemoryAllocators::Make 在 libskia.a 里；声明放这里避免拉 src/ 私有头。
namespace skgpu {
enum class ThreadSafe : bool { kNo = false, kYes = true };
namespace VulkanMemoryAllocators {
sk_sp<VulkanMemoryAllocator> Make(const VulkanBackendContext&, ThreadSafe);
}  // namespace VulkanMemoryAllocators
}  // namespace skgpu

#ifdef CreateSemaphore
#undef CreateSemaphore
#endif

namespace {

void vkLog(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    std::fprintf(stderr, "composekn: %s\n", buffer);
    std::fflush(stderr);
}

struct SwapchainImage {
    VkImage image = VK_NULL_HANDLE;
    VkSemaphore renderDone = VK_NULL_HANDLE;
    sk_sp<SkSurface> surface;
};

// Process-wide Vulkan/Graphite device (refcount). Per-window state stays on ComposeKNVkContext.
struct ComposeKNVkShared {
    int refcount = 0;
    // First window's wl_display (ComposeKN process-shared display).
    struct wl_display* display = nullptr;

    void* vulkanLib = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    VkQueue presentQueue = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamily = 0;
    uint32_t presentQueueFamily = 0;
    uint32_t apiVersion = VK_API_VERSION_1_1;

    skgpu::VulkanExtensions extensions;
    skgpu::VulkanPreferredFeatures preferredFeatures;
    VkPhysicalDeviceFeatures2 deviceFeatures2{};
    sk_sp<skgpu::VulkanMemoryAllocator> memoryAllocator;

    std::unique_ptr<skgpu::graphite::Context> graphite;

    PFN_vkDestroyInstance DestroyInstance = nullptr;
    PFN_vkDestroyDevice DestroyDevice = nullptr;
    PFN_vkDeviceWaitIdle DeviceWaitIdle = nullptr;
    PFN_vkQueueWaitIdle QueueWaitIdle = nullptr;
    PFN_vkGetDeviceQueue GetDeviceQueue = nullptr;
    PFN_vkQueueSubmit QueueSubmit = nullptr;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR = nullptr;
    PFN_vkCreateWaylandSurfaceKHR CreateWaylandSurfaceKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR = nullptr;
    PFN_vkQueuePresentKHR QueuePresentKHR = nullptr;
    PFN_vkCreateSemaphore CreateSemaphore = nullptr;
    PFN_vkDestroySemaphore DestroySemaphore = nullptr;
    PFN_vkCreateImage CreateImage = nullptr;
    PFN_vkDestroyImage DestroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements = nullptr;
    PFN_vkAllocateMemory AllocateMemory = nullptr;
    PFN_vkFreeMemory FreeMemory = nullptr;
    PFN_vkBindImageMemory BindImageMemory = nullptr;
    PFN_vkCreateCommandPool CreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool DestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer BeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer EndCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyImage CmdCopyImage = nullptr;
    PFN_vkCreateFence CreateFence = nullptr;
    PFN_vkDestroyFence DestroyFence = nullptr;
    PFN_vkWaitForFences WaitForFences = nullptr;
    PFN_vkResetFences ResetFences = nullptr;
    PFN_vkResetCommandBuffer ResetCommandBuffer = nullptr;
};

ComposeKNVkShared* g_vkShared = nullptr;

struct ComposeKNVkContext {
    ComposeKNWindow* window = nullptr;
    struct wl_display* display = nullptr;
    struct wl_surface* wlSurface = nullptr;

    ComposeKNVkShared* shared = nullptr;

    // Mirrored from shared for minimal call-site churn (non-owning).
    void* vulkanLib = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    VkQueue presentQueue = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamily = 0;
    uint32_t presentQueueFamily = 0;
    uint32_t apiVersion = VK_API_VERSION_1_1;

    // Non-owning; owned by ComposeKNVkShared.
    skgpu::graphite::Context* graphite = nullptr;
    // Per-window recorder from shared graphite->makeRecorder().
    std::unique_ptr<skgpu::graphite::Recorder> recorder;

    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat swapchainFormat = VK_FORMAT_UNDEFINED;
    VkImageUsageFlags swapchainUsage = 0;
    VkSharingMode swapchainSharing = VK_SHARING_MODE_EXCLUSIVE;
    int width = 0;
    int height = 0;
    std::vector<SwapchainImage> images;
    uint32_t currentImage = 0;
    VkSemaphore acquireSemaphore = VK_NULL_HANDLE;

    // Wayland/Intel 等 surface 常不支持 INPUT_ATTACHMENT（usage 只有 0x17），
    // Graphite 无法 Wrap swapchain → 画到自建 offscreen RT，再 blit/copy 呈现。
    bool useOffscreenBlit = false;
    sk_sp<SkSurface> offscreenSurface;
    VkImage offscreenImage = VK_NULL_HANDLE;
    VkDeviceMemory offscreenMemory = VK_NULL_HANDLE;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    VkCommandBuffer blitCmd = VK_NULL_HANDLE;
    VkFence blitFence = VK_NULL_HANDLE;

    // Cached procs (mirrored from shared)
    PFN_vkDestroyInstance DestroyInstance = nullptr;
    PFN_vkDestroyDevice DestroyDevice = nullptr;
    PFN_vkDeviceWaitIdle DeviceWaitIdle = nullptr;
    PFN_vkQueueWaitIdle QueueWaitIdle = nullptr;
    PFN_vkGetDeviceQueue GetDeviceQueue = nullptr;
    PFN_vkQueueSubmit QueueSubmit = nullptr;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR = nullptr;
    PFN_vkCreateWaylandSurfaceKHR CreateWaylandSurfaceKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR = nullptr;
    PFN_vkQueuePresentKHR QueuePresentKHR = nullptr;
    PFN_vkCreateSemaphore CreateSemaphore = nullptr;
    PFN_vkDestroySemaphore DestroySemaphore = nullptr;
    PFN_vkCreateImage CreateImage = nullptr;
    PFN_vkDestroyImage DestroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements = nullptr;
    PFN_vkAllocateMemory AllocateMemory = nullptr;
    PFN_vkFreeMemory FreeMemory = nullptr;
    PFN_vkBindImageMemory BindImageMemory = nullptr;
    PFN_vkCreateCommandPool CreateCommandPool = nullptr;
    PFN_vkDestroyCommandPool DestroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers = nullptr;
    PFN_vkBeginCommandBuffer BeginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer EndCommandBuffer = nullptr;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyImage CmdCopyImage = nullptr;
    PFN_vkCreateFence CreateFence = nullptr;
    PFN_vkDestroyFence DestroyFence = nullptr;
    PFN_vkWaitForFences WaitForFences = nullptr;
    PFN_vkResetFences ResetFences = nullptr;
    PFN_vkResetCommandBuffer ResetCommandBuffer = nullptr;
};

constexpr size_t kMaxVkWindows = 64;
struct VkWindowEntry {
    ComposeKNWindow* window = nullptr;
    ComposeKNVkContext* ctx = nullptr;
};
VkWindowEntry g_vkByWindow[kMaxVkWindows] = {};
size_t g_vkWindowCount = 0;

ComposeKNVkContext* vkOf(ComposeKNWindow* window) {
    if (window == nullptr) return nullptr;
    for (size_t i = 0; i < g_vkWindowCount; ++i) {
        if (g_vkByWindow[i].window == window) {
            return g_vkByWindow[i].ctx;
        }
    }
    return nullptr;
}

bool vkInsert(ComposeKNWindow* window, ComposeKNVkContext* ctx) {
    if (window == nullptr || ctx == nullptr) return false;
    if (vkOf(window) != nullptr) return true;
    if (g_vkWindowCount >= kMaxVkWindows) {
        vkLog("vk: too many vulkan windows (max %zu)", kMaxVkWindows);
        return false;
    }
    g_vkByWindow[g_vkWindowCount++] = {window, ctx};
    return true;
}

void vkErase(ComposeKNWindow* window) {
    for (size_t i = 0; i < g_vkWindowCount; ++i) {
        if (g_vkByWindow[i].window == window) {
            g_vkByWindow[i] = g_vkByWindow[g_vkWindowCount - 1];
            g_vkByWindow[g_vkWindowCount - 1] = {};
            --g_vkWindowCount;
            return;
        }
    }
}

void bindSharedToCtx(ComposeKNVkContext* ctx, ComposeKNVkShared* s) {
    if (!ctx || !s) return;
    ctx->shared = s;
    ctx->vulkanLib = s->vulkanLib;
    ctx->getInstanceProcAddr = s->getInstanceProcAddr;
    ctx->getDeviceProcAddr = s->getDeviceProcAddr;
    ctx->instance = s->instance;
    ctx->physicalDevice = s->physicalDevice;
    ctx->device = s->device;
    ctx->graphicsQueue = s->graphicsQueue;
    ctx->presentQueue = s->presentQueue;
    ctx->graphicsQueueFamily = s->graphicsQueueFamily;
    ctx->presentQueueFamily = s->presentQueueFamily;
    ctx->apiVersion = s->apiVersion;
    ctx->graphite = s->graphite.get();
    ctx->DestroyInstance = s->DestroyInstance;
    ctx->DestroyDevice = s->DestroyDevice;
    ctx->DeviceWaitIdle = s->DeviceWaitIdle;
    ctx->QueueWaitIdle = s->QueueWaitIdle;
    ctx->GetDeviceQueue = s->GetDeviceQueue;
    ctx->QueueSubmit = s->QueueSubmit;
    ctx->DestroySurfaceKHR = s->DestroySurfaceKHR;
    ctx->CreateWaylandSurfaceKHR = s->CreateWaylandSurfaceKHR;
    ctx->GetPhysicalDeviceSurfaceSupportKHR = s->GetPhysicalDeviceSurfaceSupportKHR;
    ctx->GetPhysicalDeviceSurfaceCapabilitiesKHR = s->GetPhysicalDeviceSurfaceCapabilitiesKHR;
    ctx->GetPhysicalDeviceSurfaceFormatsKHR = s->GetPhysicalDeviceSurfaceFormatsKHR;
    ctx->GetPhysicalDeviceSurfacePresentModesKHR = s->GetPhysicalDeviceSurfacePresentModesKHR;
    ctx->GetPhysicalDeviceMemoryProperties = s->GetPhysicalDeviceMemoryProperties;
    ctx->CreateSwapchainKHR = s->CreateSwapchainKHR;
    ctx->DestroySwapchainKHR = s->DestroySwapchainKHR;
    ctx->GetSwapchainImagesKHR = s->GetSwapchainImagesKHR;
    ctx->AcquireNextImageKHR = s->AcquireNextImageKHR;
    ctx->QueuePresentKHR = s->QueuePresentKHR;
    ctx->CreateSemaphore = s->CreateSemaphore;
    ctx->DestroySemaphore = s->DestroySemaphore;
    ctx->CreateImage = s->CreateImage;
    ctx->DestroyImage = s->DestroyImage;
    ctx->GetImageMemoryRequirements = s->GetImageMemoryRequirements;
    ctx->AllocateMemory = s->AllocateMemory;
    ctx->FreeMemory = s->FreeMemory;
    ctx->BindImageMemory = s->BindImageMemory;
    ctx->CreateCommandPool = s->CreateCommandPool;
    ctx->DestroyCommandPool = s->DestroyCommandPool;
    ctx->AllocateCommandBuffers = s->AllocateCommandBuffers;
    ctx->BeginCommandBuffer = s->BeginCommandBuffer;
    ctx->EndCommandBuffer = s->EndCommandBuffer;
    ctx->CmdPipelineBarrier = s->CmdPipelineBarrier;
    ctx->CmdCopyImage = s->CmdCopyImage;
    ctx->CreateFence = s->CreateFence;
    ctx->DestroyFence = s->DestroyFence;
    ctx->WaitForFences = s->WaitForFences;
    ctx->ResetFences = s->ResetFences;
    ctx->ResetCommandBuffer = s->ResetCommandBuffer;
}

void clearSharedMirrors(ComposeKNVkContext* ctx) {
    if (!ctx) return;
    ctx->shared = nullptr;
    ctx->vulkanLib = nullptr;
    ctx->getInstanceProcAddr = nullptr;
    ctx->getDeviceProcAddr = nullptr;
    ctx->instance = VK_NULL_HANDLE;
    ctx->physicalDevice = VK_NULL_HANDLE;
    ctx->device = VK_NULL_HANDLE;
    ctx->graphicsQueue = VK_NULL_HANDLE;
    ctx->presentQueue = VK_NULL_HANDLE;
    ctx->graphicsQueueFamily = 0;
    ctx->presentQueueFamily = 0;
    ctx->apiVersion = VK_API_VERSION_1_1;
    ctx->graphite = nullptr;
    ctx->DestroyInstance = nullptr;
    ctx->DestroyDevice = nullptr;
    ctx->DeviceWaitIdle = nullptr;
    ctx->QueueWaitIdle = nullptr;
    ctx->GetDeviceQueue = nullptr;
    ctx->QueueSubmit = nullptr;
    ctx->DestroySurfaceKHR = nullptr;
    ctx->CreateWaylandSurfaceKHR = nullptr;
    ctx->GetPhysicalDeviceSurfaceSupportKHR = nullptr;
    ctx->GetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
    ctx->GetPhysicalDeviceSurfaceFormatsKHR = nullptr;
    ctx->GetPhysicalDeviceSurfacePresentModesKHR = nullptr;
    ctx->GetPhysicalDeviceMemoryProperties = nullptr;
    ctx->CreateSwapchainKHR = nullptr;
    ctx->DestroySwapchainKHR = nullptr;
    ctx->GetSwapchainImagesKHR = nullptr;
    ctx->AcquireNextImageKHR = nullptr;
    ctx->QueuePresentKHR = nullptr;
    ctx->CreateSemaphore = nullptr;
    ctx->DestroySemaphore = nullptr;
    ctx->CreateImage = nullptr;
    ctx->DestroyImage = nullptr;
    ctx->GetImageMemoryRequirements = nullptr;
    ctx->AllocateMemory = nullptr;
    ctx->FreeMemory = nullptr;
    ctx->BindImageMemory = nullptr;
    ctx->CreateCommandPool = nullptr;
    ctx->DestroyCommandPool = nullptr;
    ctx->AllocateCommandBuffers = nullptr;
    ctx->BeginCommandBuffer = nullptr;
    ctx->EndCommandBuffer = nullptr;
    ctx->CmdPipelineBarrier = nullptr;
    ctx->CmdCopyImage = nullptr;
    ctx->CreateFence = nullptr;
    ctx->DestroyFence = nullptr;
    ctx->WaitForFences = nullptr;
    ctx->ResetFences = nullptr;
    ctx->ResetCommandBuffer = nullptr;
}

PFN_vkVoidFunction getProc(ComposeKNVkShared* shared,
                           const char* name,
                           VkInstance instance,
                           VkDevice device) {
    if (!shared || !name) return nullptr;

    // Device-level：必须走 vkGetDeviceProcAddr（不能靠 gipa(instance, …)）。
    if (device != VK_NULL_HANDLE) {
        if (!shared->getDeviceProcAddr) {
            VkInstance instForGdpa =
                    (instance != VK_NULL_HANDLE) ? instance : shared->instance;
            if (shared->getInstanceProcAddr && instForGdpa != VK_NULL_HANDLE) {
                shared->getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                        shared->getInstanceProcAddr(instForGdpa, "vkGetDeviceProcAddr"));
            }
            if (!shared->getDeviceProcAddr && shared->getInstanceProcAddr) {
                shared->getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                        shared->getInstanceProcAddr(VK_NULL_HANDLE, "vkGetDeviceProcAddr"));
            }
            if (!shared->getDeviceProcAddr && shared->vulkanLib) {
                shared->getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                        dlsym(shared->vulkanLib, "vkGetDeviceProcAddr"));
            }
        }
        if (shared->getDeviceProcAddr) {
            if (PFN_vkVoidFunction p = shared->getDeviceProcAddr(device, name)) {
                return p;
            }
        }
    }

    // Instance-level
    if (instance != VK_NULL_HANDLE && shared->getInstanceProcAddr) {
        if (PFN_vkVoidFunction p = shared->getInstanceProcAddr(instance, name)) {
            return p;
        }
    }

    // Global（CreateInstance 之前 / MakeInterface 校验 CreateInstance 等）：
    // 优先 dlsym 导出表，再回退 gipa(NULL)。
    if (shared->vulkanLib) {
        if (PFN_vkVoidFunction p = reinterpret_cast<PFN_vkVoidFunction>(
                    dlsym(shared->vulkanLib, name))) {
            return p;
        }
    }
    if (shared->getInstanceProcAddr) {
        return shared->getInstanceProcAddr(VK_NULL_HANDLE, name);
    }
    return nullptr;
}

bool loadVulkan(ComposeKNVkShared* shared) {
    shared->vulkanLib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!shared->vulkanLib) {
        vkLog("vk: dlopen(libvulkan.so.1) failed: %s", dlerror());
        return false;
    }
    vkLog("vk: loaded libvulkan.so.1");
    shared->getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            dlsym(shared->vulkanLib, "vkGetInstanceProcAddr"));
    if (!shared->getInstanceProcAddr) {
        vkLog("vk: vkGetInstanceProcAddr missing");
        return false;
    }
    return true;
}

// 全局入口（CreateInstance 之前）：优先 dlsym，再回退 gipa(NULL)。
template <typename T>
T loadGlobalProc(ComposeKNVkShared* shared, const char* name) {
    T viaSo = reinterpret_cast<T>(dlsym(shared->vulkanLib, name));
    if (viaSo) return viaSo;
    if (shared->getInstanceProcAddr) {
        return reinterpret_cast<T>(shared->getInstanceProcAddr(VK_NULL_HANDLE, name));
    }
    return nullptr;
}

bool createInstanceAndDevice(ComposeKNVkShared* shared) {
    auto gipa = shared->getInstanceProcAddr;
    auto enumerateInstanceExt =
            loadGlobalProc<PFN_vkEnumerateInstanceExtensionProperties>(
                    shared, "vkEnumerateInstanceExtensionProperties");
    auto createInstance =
            loadGlobalProc<PFN_vkCreateInstance>(shared, "vkCreateInstance");
    auto enumeratePhys =
            loadGlobalProc<PFN_vkEnumeratePhysicalDevices>(shared, "vkEnumeratePhysicalDevices");
    auto enumerateInstanceVersion =
            loadGlobalProc<PFN_vkEnumerateInstanceVersion>(shared, "vkEnumerateInstanceVersion");
    if (!enumerateInstanceExt || !createInstance || !enumeratePhys) {
        vkLog("vk: missing global procs (ext=%p create=%p phys=%p gipa=%p)",
              (void*)enumerateInstanceExt,
              (void*)createInstance,
              (void*)enumeratePhys,
              (void*)gipa);
        return false;
    }
    if (enumerateInstanceVersion) {
        uint32_t loaderVersion = 0;
        if (enumerateInstanceVersion(&loaderVersion) == VK_SUCCESS) {
            vkLog("vk: loader apiVersion=%u.%u.%u",
                  VK_VERSION_MAJOR(loaderVersion),
                  VK_VERSION_MINOR(loaderVersion),
                  VK_VERSION_PATCH(loaderVersion));
        }
    }

    shared->apiVersion = VK_API_VERSION_1_1;
    shared->preferredFeatures.init(shared->apiVersion);

    uint32_t extCount = 0;
    enumerateInstanceExt(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> availableExts(extCount);
    if (extCount) {
        enumerateInstanceExt(nullptr, &extCount, availableExts.data());
    }

    std::vector<const char*> instanceExts = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME,
    };
    // Preferred extras are optional — if CreateInstance fails with them, retry core-only.
    std::vector<const char*> instanceExtsPreferred = instanceExts;
    shared->preferredFeatures.addToInstanceExtensions(
            availableExts.data(), availableExts.size(), instanceExtsPreferred);

    auto tryCreateInstance = [&](const std::vector<const char*>& exts) -> VkResult {
        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "ComposeKN";
        appInfo.apiVersion = shared->apiVersion;
        VkInstanceCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ici.pApplicationInfo = &appInfo;
        ici.enabledExtensionCount = static_cast<uint32_t>(exts.size());
        ici.ppEnabledExtensionNames = exts.data();
        return createInstance(&ici, nullptr, &shared->instance);
    };

    VkResult ir = tryCreateInstance(instanceExtsPreferred);
    if (ir != VK_SUCCESS) {
        vkLog("vk: vkCreateInstance(preferred) failed result=%d; retrying core Wayland exts",
              (int)ir);
        ir = tryCreateInstance(instanceExts);
    }
    if (ir != VK_SUCCESS) {
        // Diagnose missing required WSI extensions.
        bool hasSurface = false;
        bool hasWayland = false;
        for (const auto& e : availableExts) {
            if (std::strcmp(e.extensionName, VK_KHR_SURFACE_EXTENSION_NAME) == 0) hasSurface = true;
            if (std::strcmp(e.extensionName, VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME) == 0) {
                hasWayland = true;
            }
        }
        vkLog("vk: vkCreateInstance failed result=%d has_surface=%d has_wayland_surface=%d extCount=%u",
              (int)ir, (int)hasSurface, (int)hasWayland, extCount);
        return false;
    }

    shared->DestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
            gipa(shared->instance, "vkDestroyInstance"));
    shared->CreateWaylandSurfaceKHR = reinterpret_cast<PFN_vkCreateWaylandSurfaceKHR>(
            gipa(shared->instance, "vkCreateWaylandSurfaceKHR"));
    shared->DestroySurfaceKHR = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
            gipa(shared->instance, "vkDestroySurfaceKHR"));
    shared->GetPhysicalDeviceSurfaceSupportKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
                    gipa(shared->instance, "vkGetPhysicalDeviceSurfaceSupportKHR"));
    shared->GetPhysicalDeviceSurfaceCapabilitiesKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
                    gipa(shared->instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"));
    shared->GetPhysicalDeviceSurfaceFormatsKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
                    gipa(shared->instance, "vkGetPhysicalDeviceSurfaceFormatsKHR"));
    shared->GetPhysicalDeviceSurfacePresentModesKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
                    gipa(shared->instance, "vkGetPhysicalDeviceSurfacePresentModesKHR"));

    auto enumQueueFamilies = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
            gipa(shared->instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    auto enumDeviceExt = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
            gipa(shared->instance, "vkEnumerateDeviceExtensionProperties"));
    auto createDevice = reinterpret_cast<PFN_vkCreateDevice>(
            gipa(shared->instance, "vkCreateDevice"));
    auto getPhysProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
            gipa(shared->instance, "vkGetPhysicalDeviceProperties"));
    auto getFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            gipa(shared->instance, "vkGetPhysicalDeviceFeatures2"));

    uint32_t physCount = 0;
    enumeratePhys(shared->instance, &physCount, nullptr);
    if (physCount == 0) {
        vkLog("vk: no physical devices");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(physCount);
    enumeratePhys(shared->instance, &physCount, devices.data());

    // Prefer discrete GPU with graphics + present.
    int bestScore = -1;
    for (VkPhysicalDevice pd : devices) {
        uint32_t qCount = 0;
        enumQueueFamilies(pd, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qprops(qCount);
        enumQueueFamilies(pd, &qCount, qprops.data());

        int gfx = -1;
        int present = -1;
        auto getPresentSupport =
                reinterpret_cast<PFN_vkGetPhysicalDeviceWaylandPresentationSupportKHR>(
                        gipa(shared->instance, "vkGetPhysicalDeviceWaylandPresentationSupportKHR"));
        for (uint32_t i = 0; i < qCount; ++i) {
            if (qprops[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                gfx = static_cast<int>(i);
            }
            if (getPresentSupport && shared->display &&
                getPresentSupport(pd, i, shared->display)) {
                present = static_cast<int>(i);
            }
        }
        if (gfx < 0 || present < 0) continue;

        VkPhysicalDeviceProperties props{};
        getPhysProps(pd, &props);
        int score = (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) ? 100 : 10;
        if (score > bestScore) {
            bestScore = score;
            shared->physicalDevice = pd;
            shared->graphicsQueueFamily = static_cast<uint32_t>(gfx);
            shared->presentQueueFamily = static_cast<uint32_t>(present);
        }
    }
    if (shared->physicalDevice == VK_NULL_HANDLE) {
        vkLog("vk: no suitable GPU with Wayland present");
        return false;
    }

    if (getPhysProps) {
        VkPhysicalDeviceProperties props{};
        getPhysProps(shared->physicalDevice, &props);
        vkLog("vk: device=%s type=%u api=%u.%u.%u",
              props.deviceName,
              static_cast<unsigned>(props.deviceType),
              VK_VERSION_MAJOR(props.apiVersion),
              VK_VERSION_MINOR(props.apiVersion),
              VK_VERSION_PATCH(props.apiVersion));
    }

    uint32_t devExtCount = 0;
    enumDeviceExt(shared->physicalDevice, nullptr, &devExtCount, nullptr);
    std::vector<VkExtensionProperties> availableDevExts(devExtCount);
    if (devExtCount) {
        enumDeviceExt(shared->physicalDevice, nullptr, &devExtCount, availableDevExts.data());
    }

    shared->deviceFeatures2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    shared->preferredFeatures.addFeaturesToQuery(availableDevExts.data(),
                                                 availableDevExts.size(),
                                                 shared->deviceFeatures2);
    if (getFeatures2) {
        getFeatures2(shared->physicalDevice, &shared->deviceFeatures2);
    }

    std::vector<const char*> deviceExts = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    shared->preferredFeatures.addFeaturesToEnable(deviceExts, shared->deviceFeatures2);

    float queuePriority = 1.0f;
    std::vector<VkDeviceQueueCreateInfo> queueCis;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = shared->graphicsQueueFamily;
    qci.queueCount = 1;
    qci.pQueuePriorities = &queuePriority;
    queueCis.push_back(qci);
    if (shared->presentQueueFamily != shared->graphicsQueueFamily) {
        VkDeviceQueueCreateInfo pqci = qci;
        pqci.queueFamilyIndex = shared->presentQueueFamily;
        queueCis.push_back(pqci);
    }

    VkDeviceCreateInfo dci{};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.pNext = &shared->deviceFeatures2;
    dci.queueCreateInfoCount = static_cast<uint32_t>(queueCis.size());
    dci.pQueueCreateInfos = queueCis.data();
    dci.enabledExtensionCount = static_cast<uint32_t>(deviceExts.size());
    dci.ppEnabledExtensionNames = deviceExts.data();
    // pEnabledFeatures must be null when using Features2 in pNext
    dci.pEnabledFeatures = nullptr;

    if (createDevice(shared->physicalDevice, &dci, nullptr, &shared->device) != VK_SUCCESS) {
        vkLog("vk: vkCreateDevice failed");
        return false;
    }

    shared->DestroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(
            getProc(shared, "vkDestroyDevice", shared->instance, shared->device));
    shared->DeviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(
            getProc(shared, "vkDeviceWaitIdle", shared->instance, shared->device));
    shared->QueueWaitIdle = reinterpret_cast<PFN_vkQueueWaitIdle>(
            getProc(shared, "vkQueueWaitIdle", shared->instance, shared->device));
    shared->GetDeviceQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(
            getProc(shared, "vkGetDeviceQueue", shared->instance, shared->device));
    shared->CreateSwapchainKHR = reinterpret_cast<PFN_vkCreateSwapchainKHR>(
            getProc(shared, "vkCreateSwapchainKHR", shared->instance, shared->device));
    shared->DestroySwapchainKHR = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
            getProc(shared, "vkDestroySwapchainKHR", shared->instance, shared->device));
    shared->GetSwapchainImagesKHR = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
            getProc(shared, "vkGetSwapchainImagesKHR", shared->instance, shared->device));
    shared->AcquireNextImageKHR = reinterpret_cast<PFN_vkAcquireNextImageKHR>(
            getProc(shared, "vkAcquireNextImageKHR", shared->instance, shared->device));
    shared->QueuePresentKHR = reinterpret_cast<PFN_vkQueuePresentKHR>(
            getProc(shared, "vkQueuePresentKHR", shared->instance, shared->device));
    shared->CreateSemaphore = reinterpret_cast<PFN_vkCreateSemaphore>(
            getProc(shared, "vkCreateSemaphore", shared->instance, shared->device));
    shared->DestroySemaphore = reinterpret_cast<PFN_vkDestroySemaphore>(
            getProc(shared, "vkDestroySemaphore", shared->instance, shared->device));
    shared->QueueSubmit = reinterpret_cast<PFN_vkQueueSubmit>(
            getProc(shared, "vkQueueSubmit", shared->instance, shared->device));
    shared->GetPhysicalDeviceMemoryProperties =
            reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(
                    gipa(shared->instance, "vkGetPhysicalDeviceMemoryProperties"));
    shared->CreateImage = reinterpret_cast<PFN_vkCreateImage>(
            getProc(shared, "vkCreateImage", shared->instance, shared->device));
    shared->DestroyImage = reinterpret_cast<PFN_vkDestroyImage>(
            getProc(shared, "vkDestroyImage", shared->instance, shared->device));
    shared->GetImageMemoryRequirements = reinterpret_cast<PFN_vkGetImageMemoryRequirements>(
            getProc(shared, "vkGetImageMemoryRequirements", shared->instance, shared->device));
    shared->AllocateMemory = reinterpret_cast<PFN_vkAllocateMemory>(
            getProc(shared, "vkAllocateMemory", shared->instance, shared->device));
    shared->FreeMemory = reinterpret_cast<PFN_vkFreeMemory>(
            getProc(shared, "vkFreeMemory", shared->instance, shared->device));
    shared->BindImageMemory = reinterpret_cast<PFN_vkBindImageMemory>(
            getProc(shared, "vkBindImageMemory", shared->instance, shared->device));
    shared->CreateCommandPool = reinterpret_cast<PFN_vkCreateCommandPool>(
            getProc(shared, "vkCreateCommandPool", shared->instance, shared->device));
    shared->DestroyCommandPool = reinterpret_cast<PFN_vkDestroyCommandPool>(
            getProc(shared, "vkDestroyCommandPool", shared->instance, shared->device));
    shared->AllocateCommandBuffers = reinterpret_cast<PFN_vkAllocateCommandBuffers>(
            getProc(shared, "vkAllocateCommandBuffers", shared->instance, shared->device));
    shared->BeginCommandBuffer = reinterpret_cast<PFN_vkBeginCommandBuffer>(
            getProc(shared, "vkBeginCommandBuffer", shared->instance, shared->device));
    shared->EndCommandBuffer = reinterpret_cast<PFN_vkEndCommandBuffer>(
            getProc(shared, "vkEndCommandBuffer", shared->instance, shared->device));
    shared->CmdPipelineBarrier = reinterpret_cast<PFN_vkCmdPipelineBarrier>(
            getProc(shared, "vkCmdPipelineBarrier", shared->instance, shared->device));
    shared->CmdCopyImage = reinterpret_cast<PFN_vkCmdCopyImage>(
            getProc(shared, "vkCmdCopyImage", shared->instance, shared->device));
    shared->CreateFence = reinterpret_cast<PFN_vkCreateFence>(
            getProc(shared, "vkCreateFence", shared->instance, shared->device));
    shared->DestroyFence = reinterpret_cast<PFN_vkDestroyFence>(
            getProc(shared, "vkDestroyFence", shared->instance, shared->device));
    shared->WaitForFences = reinterpret_cast<PFN_vkWaitForFences>(
            getProc(shared, "vkWaitForFences", shared->instance, shared->device));
    shared->ResetFences = reinterpret_cast<PFN_vkResetFences>(
            getProc(shared, "vkResetFences", shared->instance, shared->device));
    shared->ResetCommandBuffer = reinterpret_cast<PFN_vkResetCommandBuffer>(
            getProc(shared, "vkResetCommandBuffer", shared->instance, shared->device));

    shared->GetDeviceQueue(shared->device, shared->graphicsQueueFamily, 0, &shared->graphicsQueue);
    shared->GetDeviceQueue(shared->device, shared->presentQueueFamily, 0, &shared->presentQueue);

    std::vector<const char*> instExtNames = instanceExts;
    std::vector<const char*> devExtNames = deviceExts;
    shared->extensions.init(
            [shared](const char* name, VkInstance i, VkDevice d) {
                return getProc(shared, name, i, d);
            },
            shared->instance,
            shared->physicalDevice,
            static_cast<uint32_t>(instExtNames.size()),
            instExtNames.data(),
            static_cast<uint32_t>(devExtNames.size()),
            devExtNames.data());

    return true;
}

// Creates shared Graphite Context only; per-window Recorder is made in acquireSharedDevice.
bool createGraphite(ComposeKNVkShared* shared) {
    skgpu::VulkanBackendContext backend{};
    backend.fInstance = shared->instance;
    backend.fPhysicalDevice = shared->physicalDevice;
    backend.fDevice = shared->device;
    backend.fQueue = shared->graphicsQueue;
    backend.fGraphicsQueueIndex = shared->graphicsQueueFamily;
    backend.fMaxAPIVersion = shared->apiVersion;
    backend.fVkExtensions = &shared->extensions;
    backend.fDeviceFeatures2 = &shared->deviceFeatures2;
    backend.fGetProc = [shared](const char* name, VkInstance i, VkDevice d) {
        return getProc(shared, name, i, d);
    };
    backend.fMemoryAllocator =
            skgpu::VulkanMemoryAllocators::Make(backend, skgpu::ThreadSafe::kNo);
    if (!backend.fMemoryAllocator) {
        // 常见根因：fGetProc 对全局入口返回 NULL → MakeInterface/validate 失败。
        vkLog("vk: VulkanMemoryAllocators::Make failed "
              "(check getProc globals; device=%p queue=%p)",
              (void*)shared->device,
              (void*)shared->graphicsQueue);
        return false;
    }
    shared->memoryAllocator = backend.fMemoryAllocator;

    skgpu::graphite::ContextOptions options;
    shared->graphite = skgpu::graphite::ContextFactory::MakeVulkan(backend, options);
    if (!shared->graphite) {
        vkLog("vk: ContextFactory::MakeVulkan failed");
        return false;
    }
    return true;
}

void destroySharedResources(ComposeKNVkShared* shared) {
    if (!shared) return;
    if (shared->graphite) {
        shared->graphite->submit(skgpu::graphite::SyncToCpu::kYes);
    }
    if (shared->device != VK_NULL_HANDLE && shared->DeviceWaitIdle) {
        shared->DeviceWaitIdle(shared->device);
    }
    shared->graphite.reset();
    shared->memoryAllocator.reset();
    if (shared->device != VK_NULL_HANDLE && shared->DestroyDevice) {
        shared->DestroyDevice(shared->device, nullptr);
        shared->device = VK_NULL_HANDLE;
    }
    if (shared->instance != VK_NULL_HANDLE && shared->DestroyInstance) {
        shared->DestroyInstance(shared->instance, nullptr);
        shared->instance = VK_NULL_HANDLE;
    }
    if (shared->vulkanLib) {
        dlclose(shared->vulkanLib);
        shared->vulkanLib = nullptr;
    }
}

void releaseSharedDevice(ComposeKNVkContext* ctx) {
    if (!ctx || !ctx->shared) return;
    ComposeKNVkShared* shared = ctx->shared;
    clearSharedMirrors(ctx);
    --shared->refcount;
    if (shared->refcount > 0) {
        return;
    }
    if (g_vkShared == shared) {
        g_vkShared = nullptr;
    }
    destroySharedResources(shared);
    delete shared;
    vkLog("vk: shared device destroyed");
}

bool acquireSharedDevice(ComposeKNVkContext* ctx) {
    if (!ctx || !ctx->display) return false;

    if (g_vkShared) {
        ++g_vkShared->refcount;
        bindSharedToCtx(ctx, g_vkShared);
        ctx->recorder = g_vkShared->graphite->makeRecorder();
        if (!ctx->recorder) {
            vkLog("vk: makeRecorder failed");
            releaseSharedDevice(ctx);
            return false;
        }
        vkLog("vk: shared device acquired (refcount=%d)", g_vkShared->refcount);
        return true;
    }

    auto* shared = new ComposeKNVkShared();
    shared->refcount = 1;
    shared->display = ctx->display;
    if (!loadVulkan(shared) || !createInstanceAndDevice(shared) || !createGraphite(shared)) {
        destroySharedResources(shared);
        delete shared;
        return false;
    }
    g_vkShared = shared;
    bindSharedToCtx(ctx, shared);
    ctx->recorder = shared->graphite->makeRecorder();
    if (!ctx->recorder) {
        vkLog("vk: makeRecorder failed");
        releaseSharedDevice(ctx);
        return false;
    }
    vkLog("vk: shared device acquired (refcount=%d)", shared->refcount);
    return true;
}

bool createWaylandSurface(ComposeKNVkContext* ctx) {
    VkWaylandSurfaceCreateInfoKHR sci{};
    sci.sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR;
    sci.display = ctx->display;
    sci.surface = ctx->wlSurface;
    if (ctx->CreateWaylandSurfaceKHR(ctx->instance, &sci, nullptr, &ctx->surface) != VK_SUCCESS) {
        vkLog("vk: vkCreateWaylandSurfaceKHR failed");
        return false;
    }
    VkBool32 supported = VK_FALSE;
    ctx->GetPhysicalDeviceSurfaceSupportKHR(
            ctx->physicalDevice, ctx->presentQueueFamily, ctx->surface, &supported);
    if (!supported) {
        vkLog("vk: surface not supported for present queue");
        return false;
    }
    return true;
}

void resetSwapchainImages(ComposeKNVkContext* ctx) {
    for (auto& img : ctx->images) {
        img.surface.reset();
        if (img.renderDone != VK_NULL_HANDLE && ctx->DestroySemaphore) {
            ctx->DestroySemaphore(ctx->device, img.renderDone, nullptr);
            img.renderDone = VK_NULL_HANDLE;
        }
        img.image = VK_NULL_HANDLE;
    }
    ctx->images.clear();
}

void destroyOffscreen(ComposeKNVkContext* ctx) {
    if (!ctx) return;
    ctx->offscreenSurface.reset();
    // Graphite 可能还握着对 offscreen 的引用；刷干净再 DestroyImage。
    if (ctx->graphite) {
        ctx->graphite->submit(skgpu::graphite::SyncToCpu::kYes);
    }
    if (ctx->device != VK_NULL_HANDLE && ctx->DeviceWaitIdle) {
        ctx->DeviceWaitIdle(ctx->device);
    }
    if (ctx->offscreenImage != VK_NULL_HANDLE && ctx->DestroyImage) {
        ctx->DestroyImage(ctx->device, ctx->offscreenImage, nullptr);
        ctx->offscreenImage = VK_NULL_HANDLE;
    }
    if (ctx->offscreenMemory != VK_NULL_HANDLE && ctx->FreeMemory) {
        ctx->FreeMemory(ctx->device, ctx->offscreenMemory, nullptr);
        ctx->offscreenMemory = VK_NULL_HANDLE;
    }
}

uint32_t findMemoryType(ComposeKNVkContext* ctx,
                        uint32_t typeBits,
                        VkMemoryPropertyFlags properties) {
    if (!ctx->GetPhysicalDeviceMemoryProperties) return UINT32_MAX;
    VkPhysicalDeviceMemoryProperties memProps{};
    ctx->GetPhysicalDeviceMemoryProperties(ctx->physicalDevice, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool ensureBlitHelpers(ComposeKNVkContext* ctx) {
    if (ctx->cmdPool != VK_NULL_HANDLE && ctx->blitCmd != VK_NULL_HANDLE &&
        ctx->blitFence != VK_NULL_HANDLE) {
        return true;
    }
    if (!ctx->CreateCommandPool || !ctx->AllocateCommandBuffers || !ctx->CreateFence) {
        return false;
    }
    if (ctx->cmdPool == VK_NULL_HANDLE) {
        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr,
                                    VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                                    ctx->graphicsQueueFamily};
        if (ctx->CreateCommandPool(ctx->device, &pci, nullptr, &ctx->cmdPool) != VK_SUCCESS) {
            vkLog("vk: CreateCommandPool failed");
            return false;
        }
    }
    if (ctx->blitCmd == VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr,
                                       ctx->cmdPool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
        if (ctx->AllocateCommandBuffers(ctx->device, &ai, &ctx->blitCmd) != VK_SUCCESS) {
            vkLog("vk: AllocateCommandBuffers failed");
            return false;
        }
    }
    if (ctx->blitFence == VK_NULL_HANDLE) {
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0};
        if (ctx->CreateFence(ctx->device, &fi, nullptr, &ctx->blitFence) != VK_SUCCESS) {
            vkLog("vk: CreateFence failed");
            return false;
        }
    }
    return true;
}

bool ensureOffscreen(ComposeKNVkContext* ctx) {
    if (!ctx->recorder || ctx->width <= 0 || ctx->height <= 0) return false;
    if (ctx->offscreenSurface && ctx->offscreenSurface->width() == ctx->width &&
        ctx->offscreenSurface->height() == ctx->height) {
        return ensureBlitHelpers(ctx);
    }
    destroyOffscreen(ctx);
    if (!ensureBlitHelpers(ctx)) return false;

    // Graphite 要求 COLOR+INPUT（+SAMPLED）；自建图不受 swapchain caps 限制。
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                    VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT |
                                    VK_IMAGE_USAGE_SAMPLED_BIT |
                                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                    VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    VkImageCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = ctx->swapchainFormat;
    ici.extent = {static_cast<uint32_t>(ctx->width), static_cast<uint32_t>(ctx->height), 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = usage;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (ctx->CreateImage(ctx->device, &ici, nullptr, &ctx->offscreenImage) != VK_SUCCESS) {
        vkLog("vk: CreateImage(offscreen) failed");
        return false;
    }

    VkMemoryRequirements req{};
    ctx->GetImageMemoryRequirements(ctx->device, ctx->offscreenImage, &req);
    uint32_t memType = findMemoryType(ctx, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) {
        vkLog("vk: no DEVICE_LOCAL memory for offscreen");
        destroyOffscreen(ctx);
        return false;
    }
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, req.size, memType};
    if (ctx->AllocateMemory(ctx->device, &mai, nullptr, &ctx->offscreenMemory) != VK_SUCCESS) {
        vkLog("vk: AllocateMemory(offscreen) failed");
        destroyOffscreen(ctx);
        return false;
    }
    if (ctx->BindImageMemory(ctx->device, ctx->offscreenImage, ctx->offscreenMemory, 0) !=
        VK_SUCCESS) {
        vkLog("vk: BindImageMemory(offscreen) failed");
        destroyOffscreen(ctx);
        return false;
    }

    skgpu::graphite::VulkanTextureInfo info;
    info.fImageTiling = VK_IMAGE_TILING_OPTIMAL;
    info.fFormat = ctx->swapchainFormat;
    info.fImageUsageFlags = usage;
    info.fSharingMode = VK_SHARING_MODE_EXCLUSIVE;

    auto backendTex = skgpu::graphite::BackendTextures::MakeVulkan(
            {ctx->width, ctx->height},
            info,
            VK_IMAGE_LAYOUT_UNDEFINED,
            ctx->graphicsQueueFamily,
            ctx->offscreenImage,
            skgpu::VulkanAlloc());
    if (!backendTex.isValid()) {
        vkLog("vk: MakeVulkan(offscreen) invalid");
        destroyOffscreen(ctx);
        return false;
    }
    SkSurfaceProps props;
    ctx->offscreenSurface = SkSurfaces::WrapBackendTexture(
            ctx->recorder.get(), backendTex, SkColorSpace::MakeSRGB(), &props);
    if (!ctx->offscreenSurface) {
        vkLog("vk: WrapBackendTexture(offscreen) failed");
        destroyOffscreen(ctx);
        return false;
    }
    vkLog("vk: offscreen RT %dx%d fmt=%u (blit present path)",
          ctx->width,
          ctx->height,
          static_cast<unsigned>(ctx->swapchainFormat));
    return true;
}

void cmdImageBarrier(ComposeKNVkContext* ctx,
                     VkCommandBuffer cmd,
                     VkImage image,
                     VkImageLayout oldLayout,
                     VkImageLayout newLayout,
                     VkAccessFlags srcAccess,
                     VkAccessFlags dstAccess,
                     VkPipelineStageFlags srcStage,
                     VkPipelineStageFlags dstStage) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    ctx->CmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

bool blitOffscreenToSwapchain(ComposeKNVkContext* ctx) {
    if (!ctx->offscreenImage || ctx->currentImage >= ctx->images.size()) return false;
    if (!ctx->blitCmd || !ctx->QueueSubmit || !ctx->acquireSemaphore) return false;

    ctx->ResetCommandBuffer(ctx->blitCmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr,
                                VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr};
    if (ctx->BeginCommandBuffer(ctx->blitCmd, &bi) != VK_SUCCESS) return false;

    // Graphite 渲染目标结束后布局一般为 COLOR_ATTACHMENT_OPTIMAL。
    cmdImageBarrier(ctx,
                    ctx->blitCmd,
                    ctx->offscreenImage,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT);
    cmdImageBarrier(ctx,
                    ctx->blitCmd,
                    ctx->images[ctx->currentImage].image,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy copy{};
    copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.extent = {static_cast<uint32_t>(ctx->width), static_cast<uint32_t>(ctx->height), 1};
    ctx->CmdCopyImage(ctx->blitCmd,
                      ctx->offscreenImage,
                      VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                      ctx->images[ctx->currentImage].image,
                      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                      1,
                      &copy);

    cmdImageBarrier(ctx,
                    ctx->blitCmd,
                    ctx->images[ctx->currentImage].image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    0,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    // 下一帧 Graphite 还要从 COLOR 写 offscreen：转回 COLOR_ATTACHMENT。
    cmdImageBarrier(ctx,
                    ctx->blitCmd,
                    ctx->offscreenImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

    if (ctx->EndCommandBuffer(ctx->blitCmd) != VK_SUCCESS) return false;

    ctx->ResetFences(ctx->device, 1, &ctx->blitFence);
    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &ctx->acquireSemaphore;
    si.pWaitDstStageMask = &waitStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &ctx->blitCmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &ctx->images[ctx->currentImage].renderDone;
    if (ctx->QueueSubmit(ctx->graphicsQueue, 1, &si, ctx->blitFence) != VK_SUCCESS) {
        vkLog("vk: QueueSubmit(blit) failed");
        return false;
    }
    ctx->WaitForFences(ctx->device, 1, &ctx->blitFence, VK_TRUE, UINT64_MAX);
    // acquire 已在 submit 里等待完，可以立刻销毁。
    ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
    ctx->acquireSemaphore = VK_NULL_HANDLE;
    return true;
}

bool createSwapchain(ComposeKNVkContext* ctx, int width, int height) {
    VkSurfaceCapabilitiesKHR caps{};
    if (ctx->GetPhysicalDeviceSurfaceCapabilitiesKHR(
                ctx->physicalDevice, ctx->surface, &caps) != VK_SUCCESS) {
        return false;
    }

    uint32_t formatCount = 0;
    ctx->GetPhysicalDeviceSurfaceFormatsKHR(ctx->physicalDevice, ctx->surface, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    ctx->GetPhysicalDeviceSurfaceFormatsKHR(
            ctx->physicalDevice, ctx->surface, &formatCount, formats.data());

    VkSurfaceFormatKHR chosen = formats[0];
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_B8G8R8A8_UNORM || f.format == VK_FORMAT_R8G8B8A8_UNORM) {
            chosen = f;
            break;
        }
    }

    uint32_t modeCount = 0;
    ctx->GetPhysicalDeviceSurfacePresentModesKHR(
            ctx->physicalDevice, ctx->surface, &modeCount, nullptr);
    std::vector<VkPresentModeKHR> modes(modeCount);
    ctx->GetPhysicalDeviceSurfacePresentModesKHR(
            ctx->physicalDevice, ctx->surface, &modeCount, modes.data());
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    for (auto m : modes) {
        if (m == VK_PRESENT_MODE_MAILBOX_KHR) {
            presentMode = m;
            break;
        }
    }

    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent.width = static_cast<uint32_t>(std::max(1, width));
        extent.height = static_cast<uint32_t>(std::max(1, height));
    }
    extent.width = std::max(caps.minImageExtent.width,
                            std::min(caps.maxImageExtent.width, extent.width));
    extent.height = std::max(caps.minImageExtent.height,
                             std::min(caps.maxImageExtent.height, extent.height));

    // 已有 swapchain 且 extent 不变：不要重建（避免 dp×scale 与 client 差 1px 的死循环）。
    if (ctx->swapchain != VK_NULL_HANDLE &&
        static_cast<int>(extent.width) == ctx->width &&
        static_cast<int>(extent.height) == ctx->height) {
        return true;
    }

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }

    VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                              VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                              VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    // Graphite VulkanCaps：可渲染颜色纹理必须同时带 INPUT_ATTACHMENT（见 getTextureUsage）。
    // 上游 GraphiteNativeVulkanWindowContext 同样按 supportedUsageFlags 叠加。
    if (caps.supportedUsageFlags & VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT) {
        usage |= VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
    }
    if (caps.supportedUsageFlags & VK_IMAGE_USAGE_SAMPLED_BIT) {
        usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
    }
    if ((caps.supportedUsageFlags & usage) != usage) {
        vkLog("vk: surface usage unsupported want=0x%x have=0x%x",
              static_cast<unsigned>(usage),
              static_cast<unsigned>(caps.supportedUsageFlags));
        return false;
    }

    VkSwapchainCreateInfoKHR sci{};
    sci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    sci.surface = ctx->surface;
    sci.minImageCount = imageCount;
    sci.imageFormat = chosen.format;
    sci.imageColorSpace = chosen.colorSpace;
    sci.imageExtent = extent;
    sci.imageArrayLayers = 1;
    sci.imageUsage = usage;
    uint32_t families[] = {ctx->graphicsQueueFamily, ctx->presentQueueFamily};
    if (ctx->graphicsQueueFamily != ctx->presentQueueFamily) {
        sci.imageSharingMode = VK_SHARING_MODE_CONCURRENT;
        sci.queueFamilyIndexCount = 2;
        sci.pQueueFamilyIndices = families;
    } else {
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    }
    // 上游固定 IDENTITY；用 currentTransform 在部分旋转屏上会让 extent 语义对不上。
    sci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                               ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                               : caps.currentTransform;
    sci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                                 ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                                 : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sci.presentMode = presentMode;
    sci.clipped = VK_TRUE;
    sci.oldSwapchain = ctx->swapchain;

    VkSwapchainKHR newSwapchain = VK_NULL_HANDLE;
    if (ctx->CreateSwapchainKHR(ctx->device, &sci, nullptr, &newSwapchain) != VK_SUCCESS) {
        vkLog("vk: vkCreateSwapchainKHR failed");
        return false;
    }
    if (ctx->swapchain != VK_NULL_HANDLE) {
        ctx->DeviceWaitIdle(ctx->device);
        resetSwapchainImages(ctx);
        ctx->DestroySwapchainKHR(ctx->device, ctx->swapchain, nullptr);
    }
    ctx->swapchain = newSwapchain;
    ctx->swapchainFormat = chosen.format;
    ctx->swapchainUsage = usage;
    ctx->swapchainSharing = sci.imageSharingMode;
    ctx->width = static_cast<int>(extent.width);
    ctx->height = static_cast<int>(extent.height);

    uint32_t imgCount = 0;
    ctx->GetSwapchainImagesKHR(ctx->device, ctx->swapchain, &imgCount, nullptr);
    std::vector<VkImage> vkImages(imgCount);
    ctx->GetSwapchainImagesKHR(ctx->device, ctx->swapchain, &imgCount, vkImages.data());

    ctx->images.resize(imgCount);
    SkSurfaceProps props;
    const bool hasInputAttachment =
            (usage & VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT) != 0;
    ctx->useOffscreenBlit = !hasInputAttachment;
    if (ctx->useOffscreenBlit) {
        vkLog("vk: surface usage=0x%x lacks INPUT_ATTACHMENT — offscreen+blit",
              static_cast<unsigned>(usage));
    }

    for (uint32_t i = 0; i < imgCount; ++i) {
        ctx->images[i].image = vkImages[i];
        VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, nullptr, 0};
        ctx->CreateSemaphore(ctx->device, &semInfo, nullptr, &ctx->images[i].renderDone);
    }

    if (!ctx->useOffscreenBlit) {
        for (uint32_t i = 0; i < imgCount; ++i) {
            skgpu::graphite::VulkanTextureInfo info;
            info.fImageTiling = VK_IMAGE_TILING_OPTIMAL;
            info.fFormat = ctx->swapchainFormat;
            info.fImageUsageFlags = ctx->swapchainUsage;
            info.fSharingMode = ctx->swapchainSharing;

            auto backendTex = skgpu::graphite::BackendTextures::MakeVulkan(
                    {ctx->width, ctx->height},
                    info,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    ctx->presentQueueFamily,
                    ctx->images[i].image,
                    skgpu::VulkanAlloc());
            if (!backendTex.isValid()) {
                vkLog("vk: MakeVulkan backendTex invalid for image %u — offscreen+blit", i);
                ctx->useOffscreenBlit = true;
                break;
            }
            ctx->images[i].surface = SkSurfaces::WrapBackendTexture(
                    ctx->recorder.get(),
                    backendTex,
                    SkColorSpace::MakeSRGB(),
                    &props);
            if (!ctx->images[i].surface) {
                vkLog("vk: WrapBackendTexture failed for swapchain image %u "
                      "fmt=%u usage=0x%x — offscreen+blit",
                      i,
                      static_cast<unsigned>(ctx->swapchainFormat),
                      static_cast<unsigned>(ctx->swapchainUsage));
                ctx->useOffscreenBlit = true;
                break;
            }
        }
    }

    if (ctx->useOffscreenBlit) {
        for (auto& img : ctx->images) {
            img.surface.reset();
        }
        if (!ensureOffscreen(ctx)) {
            resetSwapchainImages(ctx);
            return false;
        }
    } else {
        destroyOffscreen(ctx);
    }

    vkLog("vk: swapchain %dx%d format=%u images=%u present=%s",
          ctx->width,
          ctx->height,
          static_cast<unsigned>(ctx->swapchainFormat),
          imgCount,
          ctx->useOffscreenBlit ? "offscreen+blit" : "direct-wrap");
    return true;
}

void destroyVk(ComposeKNVkContext* ctx) {
    if (!ctx) return;
    // Per-window teardown may still submit on shared graphite / DeviceWaitIdle.
    if (ctx->graphite) {
        ctx->graphite->submit(skgpu::graphite::SyncToCpu::kYes);
    }
    if (ctx->device != VK_NULL_HANDLE && ctx->DeviceWaitIdle) {
        ctx->DeviceWaitIdle(ctx->device);
    }
    if (ctx->acquireSemaphore != VK_NULL_HANDLE && ctx->DestroySemaphore) {
        ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
        ctx->acquireSemaphore = VK_NULL_HANDLE;
    }
    destroyOffscreen(ctx);
    if (ctx->blitFence != VK_NULL_HANDLE && ctx->DestroyFence) {
        ctx->DestroyFence(ctx->device, ctx->blitFence, nullptr);
        ctx->blitFence = VK_NULL_HANDLE;
    }
    // blitCmd 随 cmdPool 一起释放。
    ctx->blitCmd = VK_NULL_HANDLE;
    if (ctx->cmdPool != VK_NULL_HANDLE && ctx->DestroyCommandPool) {
        ctx->DestroyCommandPool(ctx->device, ctx->cmdPool, nullptr);
        ctx->cmdPool = VK_NULL_HANDLE;
    }
    resetSwapchainImages(ctx);
    if (ctx->swapchain != VK_NULL_HANDLE && ctx->DestroySwapchainKHR) {
        ctx->DestroySwapchainKHR(ctx->device, ctx->swapchain, nullptr);
        ctx->swapchain = VK_NULL_HANDLE;
    }
    if (ctx->surface != VK_NULL_HANDLE && ctx->DestroySurfaceKHR) {
        ctx->DestroySurfaceKHR(ctx->instance, ctx->surface, nullptr);
        ctx->surface = VK_NULL_HANDLE;
    }
    ctx->recorder.reset();
    // Shared instance/device/graphite/lib live until last window releases.
    releaseSharedDevice(ctx);
}

}  // namespace

extern "C" bool composekn_window_vk_create(ComposeKNWindow* window) {
    if (window == nullptr) return false;
    if (vkOf(window) != nullptr) return true;

    auto* display = static_cast<struct wl_display*>(composekn_window_wl_display(window));
    auto* surface = static_cast<struct wl_surface*>(composekn_window_wl_surface(window));
    if (display == nullptr || surface == nullptr) {
        vkLog("vk: missing wl_display/wl_surface");
        return false;
    }

    auto* ctx = new ComposeKNVkContext();
    ctx->window = window;
    ctx->display = display;
    ctx->wlSurface = surface;
    // acquireSharedDevice: first window creates shared device; later windows reuse it.
    // On surface/swapchain failure, destroyVk releases the shared ref we took.
    if (!acquireSharedDevice(ctx) || !createWaylandSurface(ctx)) {
        destroyVk(ctx);
        delete ctx;
        return false;
    }

    int w = std::max(1, composekn_window_buffer_width(window));
    int h = std::max(1, composekn_window_buffer_height(window));
    if (!createSwapchain(ctx, w, h)) {
        destroyVk(ctx);
        delete ctx;
        return false;
    }

    if (!vkInsert(window, ctx)) {
        destroyVk(ctx);
        delete ctx;
        return false;
    }
    composekn_window_set_vulkan_preferred(window, true);
    // 对齐 EGL ready：标记需要帧，否则首帧要等 Kotlin needRender。
    composekn_window_request_frame(window);
    vkLog("vk: Graphite/Vulkan ready (%dx%d)", ctx->width, ctx->height);
    return true;
}

extern "C" void* composekn_window_vk_begin_frame(ComposeKNWindow* window,
                                                 int width,
                                                 int height) {
    auto* ctx = vkOf(window);
    if (!ctx || !ctx->graphite || !ctx->recorder) return nullptr;

    // Prefer buffer pixel size from the window. Kotlin may pass 0 or a 1px-off
    // dp×scale size; rebuilding on mismatch would thrash the swapchain.
    int bw = std::max(1, composekn_window_buffer_width(window));
    int bh = std::max(1, composekn_window_buffer_height(window));
    int cw = bw;
    int ch = bh;
    if (width > 0 && height > 0 && width == bw && height == bh) {
        cw = width;
        ch = height;
    }

    if (cw != ctx->width || ch != ctx->height) {
        if (!createSwapchain(ctx, cw, ch)) return nullptr;
    }

    // 上一帧若 end_frame 失败，可能还挂着未移交的 acquire semaphore。
    if (ctx->acquireSemaphore != VK_NULL_HANDLE) {
        ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
        ctx->acquireSemaphore = VK_NULL_HANDLE;
    }
    VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, nullptr, 0};
    if (ctx->CreateSemaphore(ctx->device, &semInfo, nullptr, &ctx->acquireSemaphore) !=
        VK_SUCCESS) {
        vkLog("vk: CreateSemaphore(acquire) failed");
        return nullptr;
    }

    VkResult res = ctx->AcquireNextImageKHR(ctx->device,
                                            ctx->swapchain,
                                            UINT64_MAX,
                                            ctx->acquireSemaphore,
                                            VK_NULL_HANDLE,
                                            &ctx->currentImage);
    if (res == VK_ERROR_OUT_OF_DATE_KHR) {
        ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
        ctx->acquireSemaphore = VK_NULL_HANDLE;
        bw = std::max(1, composekn_window_buffer_width(window));
        bh = std::max(1, composekn_window_buffer_height(window));
        if (!createSwapchain(ctx, bw, bh)) return nullptr;
        if (ctx->CreateSemaphore(ctx->device, &semInfo, nullptr, &ctx->acquireSemaphore) !=
            VK_SUCCESS) {
            return nullptr;
        }
        res = ctx->AcquireNextImageKHR(ctx->device,
                                       ctx->swapchain,
                                       UINT64_MAX,
                                       ctx->acquireSemaphore,
                                       VK_NULL_HANDLE,
                                       &ctx->currentImage);
    } else if (res == VK_SUBOPTIMAL_KHR) {
        // 尺寸未变时继续用当前 image，避免 SUBOPTIMAL 每帧重建。
        bw = std::max(1, composekn_window_buffer_width(window));
        bh = std::max(1, composekn_window_buffer_height(window));
        if (bw != ctx->width || bh != ctx->height) {
            ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
            ctx->acquireSemaphore = VK_NULL_HANDLE;
            if (!createSwapchain(ctx, bw, bh)) return nullptr;
            if (ctx->CreateSemaphore(ctx->device, &semInfo, nullptr, &ctx->acquireSemaphore) !=
                VK_SUCCESS) {
                return nullptr;
            }
            res = ctx->AcquireNextImageKHR(ctx->device,
                                           ctx->swapchain,
                                           UINT64_MAX,
                                           ctx->acquireSemaphore,
                                           VK_NULL_HANDLE,
                                           &ctx->currentImage);
        }
    }
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
        vkLog("vk: AcquireNextImageKHR failed (%d)", static_cast<int>(res));
        ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
        ctx->acquireSemaphore = VK_NULL_HANDLE;
        return nullptr;
    }

    SkSurface* surface = ctx->useOffscreenBlit
                                 ? ctx->offscreenSurface.get()
                                 : ctx->images[ctx->currentImage].surface.get();
    if (!surface) return nullptr;
    return surface->getCanvas();
}

extern "C" bool composekn_window_vk_end_frame(ComposeKNWindow* window) {
    auto* ctx = vkOf(window);
    if (!ctx || !ctx->graphite || !ctx->recorder) return false;
    if (ctx->acquireSemaphore == VK_NULL_HANDLE) {
        vkLog("vk: end_frame without acquire semaphore");
        return false;
    }
    if (ctx->currentImage >= ctx->images.size()) {
        return false;
    }

    SkSurface* targetSurface = ctx->useOffscreenBlit
                                       ? ctx->offscreenSurface.get()
                                       : ctx->images[ctx->currentImage].surface.get();
    if (!targetSurface) return false;

    std::unique_ptr<skgpu::graphite::Recording> recording = ctx->recorder->snap();
    if (!recording) {
        vkLog("vk: recorder->snap() failed");
        return false;
    }

    skgpu::graphite::InsertRecordingInfo info;
    info.fRecording = recording.get();
    info.fTargetSurface = targetSurface;

    skgpu::MutableTextureState presentState;
    if (!ctx->useOffscreenBlit) {
        presentState = skgpu::MutableTextureStates::MakeVulkan(
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, ctx->presentQueueFamily);
        info.fTargetTextureState = &presentState;

        skgpu::graphite::BackendSemaphore waitSem =
                skgpu::graphite::BackendSemaphores::MakeVulkan(ctx->acquireSemaphore);
        info.fNumWaitSemaphores = 1;
        info.fWaitSemaphores = &waitSem;

        skgpu::graphite::BackendSemaphore signalSem =
                skgpu::graphite::BackendSemaphores::MakeVulkan(
                        ctx->images[ctx->currentImage].renderDone);
        info.fNumSignalSemaphores = 1;
        info.fSignalSemaphores = &signalSem;

        // GPU 用完 wait(acquire) semaphore 后由 finished-proc 销毁（对齐上游）。
        struct FinishContext {
            PFN_vkDestroySemaphore destroySemaphore;
            VkDevice device;
            VkSemaphore waitSemaphore;
        };
        auto* finishContext = new FinishContext{
                ctx->DestroySemaphore,
                ctx->device,
                ctx->acquireSemaphore,
        };
        info.fFinishedContext = finishContext;
        info.fFinishedProc = [](skgpu::graphite::GpuFinishedContext c,
                                skgpu::CallbackResult status) {
            auto* fc = reinterpret_cast<FinishContext*>(c);
            if (status != skgpu::CallbackResult::kSuccess) {
                vkLog("vk: recording finished with failure");
            }
            if (fc->destroySemaphore && fc->waitSemaphore != VK_NULL_HANDLE) {
                fc->destroySemaphore(fc->device, fc->waitSemaphore, nullptr);
            }
            delete fc;
        };
        ctx->acquireSemaphore = VK_NULL_HANDLE;

        if (ctx->graphite->insertRecording(info) != skgpu::graphite::InsertStatus::kSuccess) {
            vkLog("vk: insertRecording failed");
            ctx->DestroySemaphore(ctx->device, finishContext->waitSemaphore, nullptr);
            delete finishContext;
            return false;
        }
        ctx->graphite->submit(skgpu::graphite::SyncToCpu::kNo);
    } else {
        // Offscreen：Graphite 不碰 swapchain / acquire；画完再 blit。
        if (ctx->graphite->insertRecording(info) != skgpu::graphite::InsertStatus::kSuccess) {
            vkLog("vk: insertRecording(offscreen) failed");
            return false;
        }
        // 必须等 GPU 写完 offscreen，再 CmdCopyImage（否则读到未定义内容）。
        ctx->graphite->submit(skgpu::graphite::SyncToCpu::kYes);
        if (!blitOffscreenToSwapchain(ctx)) {
            vkLog("vk: blitOffscreenToSwapchain failed");
            return false;
        }
    }

    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &ctx->images[ctx->currentImage].renderDone;
    present.swapchainCount = 1;
    present.pSwapchains = &ctx->swapchain;
    present.pImageIndices = &ctx->currentImage;
    VkResult presentRes = ctx->QueuePresentKHR(ctx->presentQueue, &present);
    if (presentRes == VK_ERROR_OUT_OF_DATE_KHR || presentRes == VK_SUBOPTIMAL_KHR) {
        vkLog("vk: QueuePresent out-of-date/suboptimal (%d)", static_cast<int>(presentRes));
    } else if (presentRes != VK_SUCCESS) {
        vkLog("vk: QueuePresentKHR failed (%d)", static_cast<int>(presentRes));
        return false;
    }
    return true;
}

extern "C" void composekn_window_vk_destroy(ComposeKNWindow* window) {
    auto* ctx = vkOf(window);
    if (!ctx) return;
    vkErase(window);
    if (window != nullptr) {
        composekn_window_set_vulkan_preferred(window, false);
    }
    destroyVk(ctx);
    delete ctx;
    vkLog("vk: destroyed");
}

#else  // !SK_VULKAN || !SK_GRAPHITE

extern "C" bool composekn_window_vk_create(ComposeKNWindow*) { return false; }
extern "C" void* composekn_window_vk_begin_frame(ComposeKNWindow*, int, int) { return nullptr; }
extern "C" bool composekn_window_vk_end_frame(ComposeKNWindow*) { return false; }
extern "C" void composekn_window_vk_destroy(ComposeKNWindow*) {}

#endif
