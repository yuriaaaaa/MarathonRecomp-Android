#pragma once

// Android port: everything the game reads or writes lives in one folder on
// shared storage (the app asks for "All files access" before starting), so the
// player can drop in the game and pull out logs and saves with a file manager.
//
//   <root>/import  - put the game ISO / extracted folder (and DLC) here for the installer
//   <root>/game    - installed game files (written by the installer)
//   <root>/user    - config, saves, achievements
//   <root>/logs    - one log per session
//
// The launcher can move these (Soleanna Hub keeps its own folder and shares the game with an
// existing Marathon install) by setting, before the library loads:
//   MARATHON_ANDROID_ROOT   the folder itself
//   MARATHON_GAME_PARENT    the folder holding game/ and dlc/ (default: the root)
//   MARATHON_USER_DIR       config and saves (default: <root>/user)
//   MARATHON_LOG_DIR        logs (default: <root>/logs)
#include <cstdlib>
#include <filesystem>

#define MARATHON_ANDROID_DEFAULT_ROOT "/storage/emulated/0/MarathonRecomp"
#define MARATHON_ANDROID_ROOT (os::android::RootPath())

namespace os::android
{
    inline const char* EnvOr(const char* name, const char* fallback)
    {
        const char* value = std::getenv(name);
        return (value && *value) ? value : fallback;
    }

    inline const char* RootPath() { return EnvOr("MARATHON_ANDROID_ROOT", MARATHON_ANDROID_DEFAULT_ROOT); }

    inline std::filesystem::path GameParentPath() { return EnvOr("MARATHON_GAME_PARENT", RootPath()); }

    inline std::filesystem::path UserPath()
    {
        const char* dir = std::getenv("MARATHON_USER_DIR");
        return (dir && *dir) ? std::filesystem::path(dir) : std::filesystem::path(RootPath()) / "user";
    }

    inline std::filesystem::path LogPath()
    {
        const char* dir = std::getenv("MARATHON_LOG_DIR");
        return (dir && *dir) ? std::filesystem::path(dir) : std::filesystem::path(RootPath()) / "logs";
    }
}
