#include <os/logger.h>
#include <os/android/android_paths.h>

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include <algorithm>
#include <chrono>
#include <ctime>
#include <filesystem>
#include <string>
#include <vector>

// Android port: stdout/stderr (fmt::println, plume's fprintf) go nowhere on
// Android, so every session writes <root>/logs/marathon_<time>.log, mirrored to
// logcat, plus a backtrace if the process crashes.

// Host address of guest memory (set by main), so crash reports can name guest addresses.
uintptr_t g_androidGuestBase = 0;

namespace
{
    constexpr const char* kTag = "MarathonRecomp";
    constexpr size_t kKeepLogs = 10;

    struct sigaction g_oldActions[32];

    struct BacktraceState
    {
        uintptr_t frames[48];
        size_t count;
    };

    _Unwind_Reason_Code UnwindCallback(struct _Unwind_Context* context, void* arg)
    {
        auto* state = static_cast<BacktraceState*>(arg);
        uintptr_t pc = _Unwind_GetIP(context);
        if (pc && state->count < std::size(state->frames))
            state->frames[state->count++] = pc;

        return state->count < std::size(state->frames) ? _URC_NO_REASON : _URC_END_OF_STACK;
    }

    void WriteRaw(const char* text)
    {
        size_t length = strlen(text);
        write(STDERR_FILENO, text, length);
        __android_log_write(ANDROID_LOG_FATAL, kTag, text);
    }

    void DescribeAddress(char* buffer, size_t size, const char* label, uintptr_t address)
    {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void*>(address), &info) && info.dli_fname)
        {
            const char* module = strrchr(info.dli_fname, '/');
            module = module ? module + 1 : info.dli_fname;
            uintptr_t offset = address - reinterpret_cast<uintptr_t>(info.dli_fbase);
            if (info.dli_sname)
                snprintf(buffer, size, "%s %016zx %s+0x%zx (%s)\n", label, size_t(address), module, size_t(offset), info.dli_sname);
            else
                snprintf(buffer, size, "%s %016zx %s+0x%zx\n", label, size_t(address), module, size_t(offset));
        }
        else
        {
            snprintf(buffer, size, "%s %016zx\n", label, size_t(address));
        }
    }

    void CrashHandler(int signal, siginfo_t* info, void* context)
    {
        fflush(stdout);
        char buffer[512];
        snprintf(buffer, sizeof(buffer), "\n*** Crash: signal %d (code %d), fault address %p, thread %d ***\n",
            signal, info ? info->si_code : 0, info ? info->si_addr : nullptr, gettid());
        WriteRaw(buffer);

        if (info && g_androidGuestBase)
        {
            uintptr_t address = reinterpret_cast<uintptr_t>(info->si_addr);
            if (address >= g_androidGuestBase && address - g_androidGuestBase < 0x100000000ull)
            {
                snprintf(buffer, sizeof(buffer), "Fault is in guest memory: guest address 0x%08x\n",
                    unsigned(address - g_androidGuestBase));
                WriteRaw(buffer);
            }
        }

#if defined(__aarch64__)
        if (context)
        {
            auto* uc = static_cast<ucontext_t*>(context);
            DescribeAddress(buffer, sizeof(buffer), "pc", uintptr_t(uc->uc_mcontext.pc));
            WriteRaw(buffer);
            DescribeAddress(buffer, sizeof(buffer), "lr", uintptr_t(uc->uc_mcontext.regs[30]));
            WriteRaw(buffer);
        }
#endif

        BacktraceState state{};
        _Unwind_Backtrace(UnwindCallback, &state);
        for (size_t i = 0; i < state.count; i++)
        {
            char label[16];
            snprintf(label, sizeof(label), "#%02zu", i);
            DescribeAddress(buffer, sizeof(buffer), label, state.frames[i]);
            WriteRaw(buffer);
        }

        fflush(stdout);
        fsync(STDOUT_FILENO);

        // Hand over to the previous handler (debuggerd) for the tombstone.
        sigaction(signal, &g_oldActions[signal], nullptr);
        if (signal == SIGABRT)
            raise(signal);
    }

    void InstallCrashHandler()
    {
        static uint8_t alternateStack[64 * 1024];
        stack_t stack{};
        stack.ss_sp = alternateStack;
        stack.ss_size = sizeof(alternateStack);
        sigaltstack(&stack, nullptr);

        struct sigaction action{};
        action.sa_sigaction = CrashHandler;
        action.sa_flags = SA_SIGINFO | SA_ONSTACK;
        sigemptyset(&action.sa_mask);
        for (int signal : { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT })
            sigaction(signal, &action, &g_oldActions[signal]);
    }

    void PruneLogs(const std::filesystem::path& directory)
    {
        std::error_code ec;
        std::vector<std::filesystem::path> logs;
        for (const auto& entry : std::filesystem::directory_iterator(directory, ec))
        {
            if (entry.is_regular_file(ec) && entry.path().extension() == ".log")
                logs.push_back(entry.path());
        }

        if (logs.size() <= kKeepLogs)
            return;

        std::sort(logs.begin(), logs.end());
        for (size_t i = 0; i + kKeepLogs < logs.size(); i++)
            std::filesystem::remove(logs[i], ec);
    }
}

void os::logger::Init()
{
    std::error_code ec;
    std::filesystem::path directory = os::android::LogPath();
    std::filesystem::create_directories(directory, ec);

    time_t now = time(nullptr);
    tm local{};
    localtime_r(&now, &local);
    char name[64];
    strftime(name, sizeof(name), "marathon_%Y%m%d_%H%M%S.log", &local);

    int fd = open((directory / name).c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd >= 0)
    {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        PruneLogs(directory);
    }
    else
    {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Cannot create log file in %s (errno %d)", directory.c_str(), errno);
    }

    InstallCrashHandler();
}

void os::logger::Log(const std::string_view str, ELogType type, const char* func)
{
    int priority = ANDROID_LOG_INFO;
    switch (type)
    {
    case ELogType::Warning:
        priority = ANDROID_LOG_WARN;
        break;
    case ELogType::Error:
        priority = ANDROID_LOG_ERROR;
        break;
    case ELogType::Utility:
        priority = ANDROID_LOG_DEBUG;
        break;
    default:
        break;
    }

    std::string line = func ? fmt::format("[{}] {}", func, str) : std::string(str);
    __android_log_write(priority, kTag, line.c_str());
    fmt::println("{}", line);
}
