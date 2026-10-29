#pragma once

#include <Marathon.inl>

namespace lua50
{
    using lua_State = xpointer<void>;
    using lua_CFunction = be<uint32_t>;

    struct luaL_reg
    {
        xpointer<const char> name;
        lua_CFunction func;
    };

    inline void* lua_topointer(lua_State* L, int idx)
    {
        return GuestToHostFunction<void*>(sub_825D5800, L, idx);
    }

    // Returns nullptr unless the value is a string or a number.
    inline const char* lua_tostring(lua_State* L, int idx)
    {
        return GuestToHostFunction<const char*>(sub_825D5660, L, idx);
    }
}
