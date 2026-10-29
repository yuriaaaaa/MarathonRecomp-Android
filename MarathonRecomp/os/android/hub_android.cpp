// Soleanna Hub: Sonic's town as a launcher for other Android games.
//
// The hub's town mission (android/soleanna/make_hub_mod.py, loaded as a mod) calls
// ChangeArea(mission, "hub:<slot>") when Sonic enters a stage gate or accepts a mission giver's
// request. The ChangeArea Lua binding is overridden here: a "hub:" area never reaches the game,
// it asks GameActivity to open the app chosen for that slot instead, and Sonic stays in town.
//
// It also gives plume the game's current ANativeWindow, so the swap chain can be rebuilt when
// the hub comes back from another app (see recreateSurfaceIfWindowChanged in plume-android.patch).

#include <api/Marathon.h>
#include <api/lua50/lua.h>
#include <os/logger.h>
#include <ui/game_window.h>

#include <SDL.h>
#include <SDL_syswm.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <string>

namespace
{
    jclass g_activityClass = nullptr;
    jmethodID g_openSlot = nullptr;
    std::atomic<uint32_t> g_windowGeneration{ 0 };

    void OpenSlot(const char* key)
    {
        LOGFN("Soleanna Hub: slot {}", key);

        if (!g_activityClass || !g_openSlot)
            return;

        auto* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
        if (!env)
            return;

        jstring jkey = env->NewStringUTF(key);
        env->CallStaticIntMethod(g_activityClass, g_openSlot, jkey);
        if (env->ExceptionCheck())
            env->ExceptionClear();
        env->DeleteLocalRef(jkey);
    }
}

// Mission script function ChangeArea(mission, area): the mission luaL_reg table at 0x8203B8F0
// (next to CallMission, SetNextMission, MissionClear) points at sub_825194F0.
PPC_FUNC_IMPL(__imp__sub_825194F0);
PPC_FUNC(sub_825194F0)
{
    auto L = reinterpret_cast<lua50::lua_State*>(base + ctx.r3.u32);
    const char* area = lua50::lua_tostring(L, 2);
    if (area && std::strncmp(area, "hub:", 4) == 0)
    {
        OpenSlot(area + 4);
        ctx.r3.u64 = 0; // no results
        return;
    }

    __imp__sub_825194F0(ctx, base);
}

extern "C" ANativeWindow* MarathonAndroid_CurrentNativeWindow(uint32_t* generation)
{
    if (generation)
        *generation = g_windowGeneration.load();

    if (!GameWindow::s_pWindow)
        return nullptr;

    SDL_SysWMinfo info;
    SDL_VERSION(&info.version);
    if (!SDL_GetWindowWMInfo(GameWindow::s_pWindow, &info))
        return nullptr;

    return info.info.android.window;
}

// GameActivity.onCreate (Java UI thread, where the app's classes can be looked up).
extern "C" JNIEXPORT void JNICALL Java_com_marathonrecomp_android_GameActivity_nativeHubInit(JNIEnv* env, jclass cls)
{
    if (!g_activityClass)
        g_activityClass = static_cast<jclass>(env->NewGlobalRef(cls));
    g_openSlot = env->GetStaticMethodID(g_activityClass, "hubOpenSlot", "(Ljava/lang/String;)I");
    if (env->ExceptionCheck())
    {
        env->ExceptionClear();
        g_openSlot = nullptr;
    }
}

// GameActivity.onPause: Android takes the window away, so the next frame needs a new surface.
extern "C" JNIEXPORT void JNICALL Java_com_marathonrecomp_android_GameActivity_nativeWindowLost(JNIEnv*, jclass)
{
    g_windowGeneration.fetch_add(1);
}
