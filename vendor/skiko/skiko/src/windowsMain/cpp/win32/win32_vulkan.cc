/**
 * ComposeKN Windows Graphite + Vulkan 桥。
 *
 * 对照上游 tools/window/GraphiteNativeVulkanWindowContext.cpp，但：
 *   - 不依赖 tools/（自建 VkInstance/Device + Win32 surface/swapchain）；
 *   - C API 暴露给 Kotlin（begin_frame → SkCanvas*，end_frame → present）；
 *   - 动态 LoadLibrary("vulkan-1.dll")，不链 libvulkan。
 *
 * 未定义 SK_VULKAN+SK_GRAPHITE 时全部 stub 失败（链纯 GL Skia 包时仍能编过）。
 */
#include "win32_bridge.h"

#include <windows.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <unordered_map>
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

#include <vulkan/vulkan_win32.h>

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
    composekn_win32_log(buffer);
}

struct SwapchainImage {
    VkImage image = VK_NULL_HANDLE;
    VkSemaphore renderDone = VK_NULL_HANDLE;
    sk_sp<SkSurface> surface;
};

struct ComposeKNVkContext {
    HWND hwnd = nullptr;

    HMODULE vulkanLib = nullptr;
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

    // Cached procs
    PFN_vkDestroyInstance DestroyInstance = nullptr;
    PFN_vkDestroyDevice DestroyDevice = nullptr;
    PFN_vkDeviceWaitIdle DeviceWaitIdle = nullptr;
    PFN_vkQueueWaitIdle QueueWaitIdle = nullptr;
    PFN_vkGetDeviceQueue GetDeviceQueue = nullptr;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR = nullptr;
    PFN_vkCreateWin32SurfaceKHR CreateWin32SurfaceKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR = nullptr;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR = nullptr;
    PFN_vkQueuePresentKHR QueuePresentKHR = nullptr;
    PFN_vkCreateSemaphore CreateSemaphore = nullptr;
    PFN_vkDestroySemaphore DestroySemaphore = nullptr;
};

std::unordered_map<ComposeKNWin32Window*, ComposeKNVkContext*> g_vkByWindow;

ComposeKNVkContext* vkOf(ComposeKNWin32Window* window) {
    if (window == nullptr) return nullptr;
    auto it = g_vkByWindow.find(window);
    return it == g_vkByWindow.end() ? nullptr : it->second;
}

PFN_vkVoidFunction getProc(ComposeKNVkContext* ctx,
                           const char* name,
                           VkInstance instance,
                           VkDevice device) {
    if (!ctx || !name) return nullptr;

    // Device-level：必须走 vkGetDeviceProcAddr（不能靠 gipa(instance, …)）。
    if (device != VK_NULL_HANDLE) {
        if (!ctx->getDeviceProcAddr) {
            VkInstance instForGdpa =
                    (instance != VK_NULL_HANDLE) ? instance : ctx->instance;
            if (ctx->getInstanceProcAddr && instForGdpa != VK_NULL_HANDLE) {
                ctx->getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                        ctx->getInstanceProcAddr(instForGdpa, "vkGetDeviceProcAddr"));
            }
            if (!ctx->getDeviceProcAddr && ctx->getInstanceProcAddr) {
                ctx->getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                        ctx->getInstanceProcAddr(VK_NULL_HANDLE, "vkGetDeviceProcAddr"));
            }
            if (!ctx->getDeviceProcAddr && ctx->vulkanLib) {
                ctx->getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(
                        GetProcAddress(ctx->vulkanLib, "vkGetDeviceProcAddr"));
            }
        }
        if (ctx->getDeviceProcAddr) {
            if (PFN_vkVoidFunction p = ctx->getDeviceProcAddr(device, name)) {
                return p;
            }
        }
    }

    // Instance-level
    if (instance != VK_NULL_HANDLE && ctx->getInstanceProcAddr) {
        if (PFN_vkVoidFunction p = ctx->getInstanceProcAddr(instance, name)) {
            return p;
        }
    }

    // Global（CreateInstance 之前 / MakeInterface 校验 CreateInstance 等）：
    // Windows 加载器对 gipa(NULL, "vkCreateInstance") 常返回 NULL，必须先 GetProcAddress。
    if (ctx->vulkanLib) {
        if (PFN_vkVoidFunction p = reinterpret_cast<PFN_vkVoidFunction>(
                    GetProcAddress(ctx->vulkanLib, name))) {
            return p;
        }
    }
    if (ctx->getInstanceProcAddr) {
        return ctx->getInstanceProcAddr(VK_NULL_HANDLE, name);
    }
    return nullptr;
}

bool loadVulkan(ComposeKNVkContext* ctx) {
    ctx->vulkanLib = LoadLibraryA("vulkan-1.dll");
    if (!ctx->vulkanLib) {
        vkLog("vk: LoadLibrary(vulkan-1.dll) failed (%lu)", GetLastError());
        return false;
    }
    char modPath[MAX_PATH] = {};
    if (GetModuleFileNameA(ctx->vulkanLib, modPath, MAX_PATH) > 0) {
        vkLog("vk: loaded %s", modPath);
    }
    ctx->getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
            GetProcAddress(ctx->vulkanLib, "vkGetInstanceProcAddr"));
    if (!ctx->getInstanceProcAddr) {
        vkLog("vk: vkGetInstanceProcAddr missing");
        return false;
    }
    return true;
}

// 全局入口（CreateInstance 之前）：部分 Windows 加载器对
// vkGetInstanceProcAddr(NULL, "vkCreateInstance") 返回 NULL，但 DLL 导出表有这些符号。
// GLFW / SDL / Skia tools 都是优先 GetProcAddress，再回退 gipa(NULL)。
template <typename T>
T loadGlobalProc(ComposeKNVkContext* ctx, const char* name) {
    T viaDll = reinterpret_cast<T>(GetProcAddress(ctx->vulkanLib, name));
    if (viaDll) return viaDll;
    if (ctx->getInstanceProcAddr) {
        return reinterpret_cast<T>(ctx->getInstanceProcAddr(VK_NULL_HANDLE, name));
    }
    return nullptr;
}

bool createInstanceAndDevice(ComposeKNVkContext* ctx) {
    auto gipa = ctx->getInstanceProcAddr;
    auto enumerateInstanceExt =
            loadGlobalProc<PFN_vkEnumerateInstanceExtensionProperties>(
                    ctx, "vkEnumerateInstanceExtensionProperties");
    auto createInstance =
            loadGlobalProc<PFN_vkCreateInstance>(ctx, "vkCreateInstance");
    auto enumeratePhys =
            loadGlobalProc<PFN_vkEnumeratePhysicalDevices>(ctx, "vkEnumeratePhysicalDevices");
    auto enumerateInstanceVersion =
            loadGlobalProc<PFN_vkEnumerateInstanceVersion>(ctx, "vkEnumerateInstanceVersion");
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

    ctx->apiVersion = VK_API_VERSION_1_1;
    ctx->preferredFeatures.init(ctx->apiVersion);

    uint32_t extCount = 0;
    enumerateInstanceExt(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> availableExts(extCount);
    if (extCount) {
        enumerateInstanceExt(nullptr, &extCount, availableExts.data());
    }

    std::vector<const char*> instanceExts = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_WIN32_SURFACE_EXTENSION_NAME,
    };
    ctx->preferredFeatures.addToInstanceExtensions(
            availableExts.data(), availableExts.size(), instanceExts);

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "ComposeKN";
    appInfo.apiVersion = ctx->apiVersion;

    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &appInfo;
    ici.enabledExtensionCount = static_cast<uint32_t>(instanceExts.size());
    ici.ppEnabledExtensionNames = instanceExts.data();
    if (createInstance(&ici, nullptr, &ctx->instance) != VK_SUCCESS) {
        vkLog("vk: vkCreateInstance failed");
        return false;
    }

    ctx->DestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(
            gipa(ctx->instance, "vkDestroyInstance"));
    ctx->CreateWin32SurfaceKHR = reinterpret_cast<PFN_vkCreateWin32SurfaceKHR>(
            gipa(ctx->instance, "vkCreateWin32SurfaceKHR"));
    ctx->DestroySurfaceKHR = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
            gipa(ctx->instance, "vkDestroySurfaceKHR"));
    ctx->GetPhysicalDeviceSurfaceSupportKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
                    gipa(ctx->instance, "vkGetPhysicalDeviceSurfaceSupportKHR"));
    ctx->GetPhysicalDeviceSurfaceCapabilitiesKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
                    gipa(ctx->instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"));
    ctx->GetPhysicalDeviceSurfaceFormatsKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
                    gipa(ctx->instance, "vkGetPhysicalDeviceSurfaceFormatsKHR"));
    ctx->GetPhysicalDeviceSurfacePresentModesKHR =
            reinterpret_cast<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
                    gipa(ctx->instance, "vkGetPhysicalDeviceSurfacePresentModesKHR"));

    auto enumQueueFamilies = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
            gipa(ctx->instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    auto enumDeviceExt = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
            gipa(ctx->instance, "vkEnumerateDeviceExtensionProperties"));
    auto createDevice = reinterpret_cast<PFN_vkCreateDevice>(
            gipa(ctx->instance, "vkCreateDevice"));
    auto getPhysProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
            gipa(ctx->instance, "vkGetPhysicalDeviceProperties"));
    auto getFeatures2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            gipa(ctx->instance, "vkGetPhysicalDeviceFeatures2"));

    uint32_t physCount = 0;
    enumeratePhys(ctx->instance, &physCount, nullptr);
    if (physCount == 0) {
        vkLog("vk: no physical devices");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(physCount);
    enumeratePhys(ctx->instance, &physCount, devices.data());

    // Prefer discrete GPU with graphics + present.
    int bestScore = -1;
    for (VkPhysicalDevice pd : devices) {
        uint32_t qCount = 0;
        enumQueueFamilies(pd, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qprops(qCount);
        enumQueueFamilies(pd, &qCount, qprops.data());

        int gfx = -1;
        int present = -1;
        for (uint32_t i = 0; i < qCount; ++i) {
            if (qprops[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                gfx = static_cast<int>(i);
            }
            auto getPresentSupport =
                    reinterpret_cast<PFN_vkGetPhysicalDeviceWin32PresentationSupportKHR>(
                            gipa(ctx->instance, "vkGetPhysicalDeviceWin32PresentationSupportKHR"));
            if (getPresentSupport && getPresentSupport(pd, i)) {
                present = static_cast<int>(i);
            }
        }
        if (gfx < 0 || present < 0) continue;

        VkPhysicalDeviceProperties props{};
        getPhysProps(pd, &props);
        int score = (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) ? 100 : 10;
        if (score > bestScore) {
            bestScore = score;
            ctx->physicalDevice = pd;
            ctx->graphicsQueueFamily = static_cast<uint32_t>(gfx);
            ctx->presentQueueFamily = static_cast<uint32_t>(present);
        }
    }
    if (ctx->physicalDevice == VK_NULL_HANDLE) {
        vkLog("vk: no suitable GPU with Win32 present");
        return false;
    }

    if (getPhysProps) {
        VkPhysicalDeviceProperties props{};
        getPhysProps(ctx->physicalDevice, &props);
        vkLog("vk: device=%s type=%u api=%u.%u.%u",
              props.deviceName,
              static_cast<unsigned>(props.deviceType),
              VK_VERSION_MAJOR(props.apiVersion),
              VK_VERSION_MINOR(props.apiVersion),
              VK_VERSION_PATCH(props.apiVersion));
    }

    uint32_t devExtCount = 0;
    enumDeviceExt(ctx->physicalDevice, nullptr, &devExtCount, nullptr);
    std::vector<VkExtensionProperties> availableDevExts(devExtCount);
    if (devExtCount) {
        enumDeviceExt(ctx->physicalDevice, nullptr, &devExtCount, availableDevExts.data());
    }

    ctx->deviceFeatures2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    ctx->preferredFeatures.addFeaturesToQuery(availableDevExts.data(),
                                              availableDevExts.size(),
                                              ctx->deviceFeatures2);
    if (getFeatures2) {
        getFeatures2(ctx->physicalDevice, &ctx->deviceFeatures2);
    }

    std::vector<const char*> deviceExts = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    ctx->preferredFeatures.addFeaturesToEnable(deviceExts, ctx->deviceFeatures2);

    float queuePriority = 1.0f;
    std::vector<VkDeviceQueueCreateInfo> queueCis;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = ctx->graphicsQueueFamily;
    qci.queueCount = 1;
    qci.pQueuePriorities = &queuePriority;
    queueCis.push_back(qci);
    if (ctx->presentQueueFamily != ctx->graphicsQueueFamily) {
        VkDeviceQueueCreateInfo pqci = qci;
        pqci.queueFamilyIndex = ctx->presentQueueFamily;
        queueCis.push_back(pqci);
    }

    VkDeviceCreateInfo dci{};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.pNext = &ctx->deviceFeatures2;
    dci.queueCreateInfoCount = static_cast<uint32_t>(queueCis.size());
    dci.pQueueCreateInfos = queueCis.data();
    dci.enabledExtensionCount = static_cast<uint32_t>(deviceExts.size());
    dci.ppEnabledExtensionNames = deviceExts.data();
    // pEnabledFeatures must be null when using Features2 in pNext
    dci.pEnabledFeatures = nullptr;

    if (createDevice(ctx->physicalDevice, &dci, nullptr, &ctx->device) != VK_SUCCESS) {
        vkLog("vk: vkCreateDevice failed");
        return false;
    }

    ctx->DestroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(
            getProc(ctx, "vkDestroyDevice", ctx->instance, ctx->device));
    ctx->DeviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(
            getProc(ctx, "vkDeviceWaitIdle", ctx->instance, ctx->device));
    ctx->QueueWaitIdle = reinterpret_cast<PFN_vkQueueWaitIdle>(
            getProc(ctx, "vkQueueWaitIdle", ctx->instance, ctx->device));
    ctx->GetDeviceQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(
            getProc(ctx, "vkGetDeviceQueue", ctx->instance, ctx->device));
    ctx->CreateSwapchainKHR = reinterpret_cast<PFN_vkCreateSwapchainKHR>(
            getProc(ctx, "vkCreateSwapchainKHR", ctx->instance, ctx->device));
    ctx->DestroySwapchainKHR = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
            getProc(ctx, "vkDestroySwapchainKHR", ctx->instance, ctx->device));
    ctx->GetSwapchainImagesKHR = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
            getProc(ctx, "vkGetSwapchainImagesKHR", ctx->instance, ctx->device));
    ctx->AcquireNextImageKHR = reinterpret_cast<PFN_vkAcquireNextImageKHR>(
            getProc(ctx, "vkAcquireNextImageKHR", ctx->instance, ctx->device));
    ctx->QueuePresentKHR = reinterpret_cast<PFN_vkQueuePresentKHR>(
            getProc(ctx, "vkQueuePresentKHR", ctx->instance, ctx->device));
    ctx->CreateSemaphore = reinterpret_cast<PFN_vkCreateSemaphore>(
            getProc(ctx, "vkCreateSemaphore", ctx->instance, ctx->device));
    ctx->DestroySemaphore = reinterpret_cast<PFN_vkDestroySemaphore>(
            getProc(ctx, "vkDestroySemaphore", ctx->instance, ctx->device));

    ctx->GetDeviceQueue(ctx->device, ctx->graphicsQueueFamily, 0, &ctx->graphicsQueue);
    ctx->GetDeviceQueue(ctx->device, ctx->presentQueueFamily, 0, &ctx->presentQueue);

    std::vector<const char*> instExtNames = instanceExts;
    std::vector<const char*> devExtNames = deviceExts;
    ctx->extensions.init(
            [ctx](const char* name, VkInstance i, VkDevice d) {
                return getProc(ctx, name, i, d);
            },
            ctx->instance,
            ctx->physicalDevice,
            static_cast<uint32_t>(instExtNames.size()),
            instExtNames.data(),
            static_cast<uint32_t>(devExtNames.size()),
            devExtNames.data());

    return true;
}

bool createGraphite(ComposeKNVkContext* ctx) {
    skgpu::VulkanBackendContext backend{};
    backend.fInstance = ctx->instance;
    backend.fPhysicalDevice = ctx->physicalDevice;
    backend.fDevice = ctx->device;
    backend.fQueue = ctx->graphicsQueue;
    backend.fGraphicsQueueIndex = ctx->graphicsQueueFamily;
    backend.fMaxAPIVersion = ctx->apiVersion;
    backend.fVkExtensions = &ctx->extensions;
    backend.fDeviceFeatures2 = &ctx->deviceFeatures2;
    backend.fGetProc = [ctx](const char* name, VkInstance i, VkDevice d) {
        return getProc(ctx, name, i, d);
    };
    backend.fMemoryAllocator =
            skgpu::VulkanMemoryAllocators::Make(backend, skgpu::ThreadSafe::kNo);
    if (!backend.fMemoryAllocator) {
        // 常见根因：fGetProc 对全局入口返回 NULL → MakeInterface/validate 失败。
        vkLog("vk: VulkanMemoryAllocators::Make failed "
              "(check getProc globals; device=%p queue=%p)",
              (void*)ctx->device,
              (void*)ctx->graphicsQueue);
        return false;
    }
    ctx->memoryAllocator = backend.fMemoryAllocator;

    skgpu::graphite::ContextOptions options;
    ctx->graphite = skgpu::graphite::ContextFactory::MakeVulkan(backend, options);
    if (!ctx->graphite) {
        vkLog("vk: ContextFactory::MakeVulkan failed");
        return false;
    }
    ctx->recorder = ctx->graphite->makeRecorder();
    if (!ctx->recorder) {
        vkLog("vk: makeRecorder failed");
        return false;
    }
    return true;
}

bool createWin32Surface(ComposeKNVkContext* ctx) {
    VkWin32SurfaceCreateInfoKHR sci{};
    sci.sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR;
    sci.hinstance = GetModuleHandleW(nullptr);
    sci.hwnd = ctx->hwnd;
    if (ctx->CreateWin32SurfaceKHR(ctx->instance, &sci, nullptr, &ctx->surface) != VK_SUCCESS) {
        vkLog("vk: vkCreateWin32SurfaceKHR failed");
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
        extent.width = static_cast<uint32_t>(width);
        extent.height = static_cast<uint32_t>(height);
    }
    extent.width = std::max(caps.minImageExtent.width,
                            std::min(caps.maxImageExtent.width, extent.width));
    extent.height = std::max(caps.minImageExtent.height,
                             std::min(caps.maxImageExtent.height, extent.height));

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        imageCount = caps.maxImageCount;
    }

    VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                              VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                              VK_IMAGE_USAGE_TRANSFER_DST_BIT;

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
    sci.preTransform = caps.currentTransform;
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
    for (uint32_t i = 0; i < imgCount; ++i) {
        ctx->images[i].image = vkImages[i];
        VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, nullptr, 0};
        ctx->CreateSemaphore(ctx->device, &semInfo, nullptr, &ctx->images[i].renderDone);

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
        ctx->images[i].surface = SkSurfaces::WrapBackendTexture(
                ctx->recorder.get(),
                backendTex,
                SkColorSpace::MakeSRGB(),
                &props);
        if (!ctx->images[i].surface) {
            vkLog("vk: WrapBackendTexture failed for swapchain image %u", i);
            resetSwapchainImages(ctx);
            return false;
        }
    }
    vkLog("vk: swapchain %dx%d format=%u images=%u",
          ctx->width,
          ctx->height,
          static_cast<unsigned>(ctx->swapchainFormat),
          imgCount);
    return true;
}

void destroyVk(ComposeKNVkContext* ctx) {
    if (!ctx) return;
    // 先让 Graphite 把未完成的 Recording 刷完，finished-proc 才能安全销毁 acquire sem。
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
    ctx->graphite.reset();
    ctx->memoryAllocator.reset();
    if (ctx->device != VK_NULL_HANDLE && ctx->DestroyDevice) {
        ctx->DestroyDevice(ctx->device, nullptr);
        ctx->device = VK_NULL_HANDLE;
    }
    if (ctx->instance != VK_NULL_HANDLE && ctx->DestroyInstance) {
        ctx->DestroyInstance(ctx->instance, nullptr);
        ctx->instance = VK_NULL_HANDLE;
    }
    if (ctx->vulkanLib) {
        FreeLibrary(ctx->vulkanLib);
        ctx->vulkanLib = nullptr;
    }
}

}  // namespace

extern "C" bool composekn_win32_vk_create(ComposeKNWin32Window* window) {
    if (window == nullptr) return false;
    if (vkOf(window) != nullptr) return true;

    HWND hwnd = static_cast<HWND>(composekn_win32_hwnd(window));
    if (hwnd == nullptr) return false;

    auto* ctx = new ComposeKNVkContext();
    ctx->hwnd = hwnd;
    if (!loadVulkan(ctx) || !createInstanceAndDevice(ctx) || !createGraphite(ctx) ||
        !createWin32Surface(ctx)) {
        destroyVk(ctx);
        delete ctx;
        return false;
    }

    RECT rc{};
    GetClientRect(hwnd, &rc);
    int w = std::max(1, static_cast<int>(rc.right - rc.left));
    int h = std::max(1, static_cast<int>(rc.bottom - rc.top));
    if (!createSwapchain(ctx, w, h)) {
        destroyVk(ctx);
        delete ctx;
        return false;
    }

    g_vkByWindow[window] = ctx;
    vkLog("vk: Graphite/Vulkan ready (%dx%d)", ctx->width, ctx->height);
    return true;
}

extern "C" void* composekn_win32_vk_begin_frame(ComposeKNWin32Window* window,
                                                int width,
                                                int height) {
    auto* ctx = vkOf(window);
    if (!ctx || !ctx->graphite || !ctx->recorder) return nullptr;

    if (width <= 0 || height <= 0) return nullptr;
    if (width != ctx->width || height != ctx->height) {
        if (!createSwapchain(ctx, width, height)) return nullptr;
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
    if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR) {
        // swapchain 重建后旧 acquire semaphore 不可复用：拆掉再建一枚。
        ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
        ctx->acquireSemaphore = VK_NULL_HANDLE;
        if (!createSwapchain(ctx, width, height)) return nullptr;
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
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
        vkLog("vk: AcquireNextImageKHR failed (%d)", static_cast<int>(res));
        ctx->DestroySemaphore(ctx->device, ctx->acquireSemaphore, nullptr);
        ctx->acquireSemaphore = VK_NULL_HANDLE;
        return nullptr;
    }

    SkSurface* surface = ctx->images[ctx->currentImage].surface.get();
    if (!surface) return nullptr;
    return surface->getCanvas();
}

extern "C" bool composekn_win32_vk_end_frame(ComposeKNWin32Window* window) {
    auto* ctx = vkOf(window);
    if (!ctx || !ctx->graphite || !ctx->recorder) return false;
    if (ctx->acquireSemaphore == VK_NULL_HANDLE) {
        vkLog("vk: end_frame without acquire semaphore");
        return false;
    }
    if (ctx->currentImage >= ctx->images.size() || !ctx->images[ctx->currentImage].surface) {
        return false;
    }

    std::unique_ptr<skgpu::graphite::Recording> recording = ctx->recorder->snap();
    if (!recording) {
        vkLog("vk: recorder->snap() failed");
        return false;
    }

    skgpu::graphite::InsertRecordingInfo info;
    info.fRecording = recording.get();
    info.fTargetSurface = ctx->images[ctx->currentImage].surface.get();

    skgpu::MutableTextureState presentState = skgpu::MutableTextureStates::MakeVulkan(
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

    // GPU 用完 wait(acquire) semaphore 后由 finished-proc 销毁（对齐上游
    // GraphiteNativeVulkanWindowContext），避免 QueueWaitIdle 卡帧。
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
    // 所有权交给 finished-proc；destroyVk / 下一帧 begin 不再碰这枚。
    ctx->acquireSemaphore = VK_NULL_HANDLE;

    if (ctx->graphite->insertRecording(info) != skgpu::graphite::InsertStatus::kSuccess) {
        vkLog("vk: insertRecording failed");
        // insert 失败时 finished-proc 可能不会跑——自己清掉。
        ctx->DestroySemaphore(ctx->device, finishContext->waitSemaphore, nullptr);
        delete finishContext;
        return false;
    }
    ctx->graphite->submit(skgpu::graphite::SyncToCpu::kNo);

    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &ctx->images[ctx->currentImage].renderDone;
    present.swapchainCount = 1;
    present.pSwapchains = &ctx->swapchain;
    present.pImageIndices = &ctx->currentImage;
    VkResult presentRes = ctx->QueuePresentKHR(ctx->presentQueue, &present);
    if (presentRes == VK_ERROR_OUT_OF_DATE_KHR || presentRes == VK_SUBOPTIMAL_KHR) {
        // 下一帧 begin 会按客户区尺寸重建；本帧仍算成功呈现尝试。
        vkLog("vk: QueuePresent out-of-date/suboptimal (%d)", static_cast<int>(presentRes));
    } else if (presentRes != VK_SUCCESS) {
        vkLog("vk: QueuePresentKHR failed (%d)", static_cast<int>(presentRes));
        return false;
    }
    return true;
}

extern "C" void composekn_win32_vk_destroy(ComposeKNWin32Window* window) {
    auto* ctx = vkOf(window);
    if (!ctx) return;
    g_vkByWindow.erase(window);
    destroyVk(ctx);
    delete ctx;
    vkLog("vk: destroyed");
}

#else  // !SK_VULKAN || !SK_GRAPHITE

extern "C" bool composekn_win32_vk_create(ComposeKNWin32Window*) { return false; }
extern "C" void* composekn_win32_vk_begin_frame(ComposeKNWin32Window*, int, int) { return nullptr; }
extern "C" bool composekn_win32_vk_end_frame(ComposeKNWin32Window*) { return false; }
extern "C" void composekn_win32_vk_destroy(ComposeKNWin32Window*) {}

#endif
