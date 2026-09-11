// Glibc 2.32+ TLS optimization symbol referenced by newer C++ runtimes / LLVM linked objects.
// Provide a weak stub when linking on distros where the symbol is absent from libc.
extern "C" __attribute__((weak)) char __libc_single_threaded = 0;
