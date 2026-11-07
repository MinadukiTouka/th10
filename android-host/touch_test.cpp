// Host-side test for the touch policy used by the Android host.
//
// This is NOT part of the APK: it compiles the very same header the port ships
// (portable/input/TouchController.hpp) with the host compiler and checks the
// wiring AndroidMain.cpp relies on:
//
//   holding a finger          -> keys[90]  (Z, shot)
//   two fingers               -> keys[16]  (Shift, focus)
//   bumping the bomb serial   -> keys[88]  (X) for 3 samples
//   bumping the escape serial -> keys[27]  (Esc) for 3 samples
//   dragging                  -> motion/x/y towards the drag target
//
// Build and run (MinGW g++ from C:\th08-toolchain):
//   g++ -std=c++17 -O1 -I th10-port/portable/input touch_test.cpp -o touch_test.exe
//   ./touch_test.exe
#include "TouchController.hpp"

#include <cmath>
#include <cstdio>
#include <cstdint>

using touhou::input::TouchController;
using touhou::input::TouchSample;
using touhou::input::TouchState;

static int failures = 0;
static void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

int main() {
    // A playing state: context 1 (gameplay), instance 7, player at (0,0).
    TouchState playing;
    playing.context = 1;
    playing.instance = 7;
    playing.ready = true;
    playing.x = 0;
    playing.y = 100;   // inside the playfield (min_y 32)
    playing.fast = 4.f;
    playing.slow = 2.f;
    playing.min_x = -184; playing.max_x = 184;
    playing.min_y = 32;   playing.max_y = 432;

    // 1. No fingers, no shot.
    {
        TouchController c;
        c.controls(false, false, 0, 0, 0, 0);
        const auto s = c.sample(playing, 1000, false, false);
        check(!s.keys[90], "idle: no shot without a finger");
        check(s.motion == 0, "idle: no motion without a finger");
    }

    // 2. Finger down + controls(fire=true) -> shot, and a drag moves the target.
    {
        TouchController c;
        c.pointer(0, 1, 0.5f, 0.5f, 1000, playing, false);   // DOWN at centre
        c.controls(true, false, 0, 0, 0, 0);                 // holding = shoot
        auto s = c.sample(playing, 1000, false, false);
        check(s.keys[90], "one finger: shot is held");
        check(s.motion == 1, "one finger: drag mode reports motion");
        c.pointer(1, 1, 0.6f, 0.5f, 1016, playing, false);   // MOVE 64px right
        s = c.sample(playing, 1016, false, false);
        check(s.motion == 1 && s.x > 0.f, "drag: target moved to the right");
        check(s.y == 100.f, "drag: vertical target unchanged");
    }

    // 3. Two fingers -> focus (Shift = vk 16).
    {
        TouchController c;
        c.pointer(0, 1, 0.5f, 0.5f, 1000, playing, false);
        c.pointer(0, 2, 0.6f, 0.5f, 1000, playing, false);
        c.controls(true, true, 0, 0, 0, 0);
        const auto s = c.sample(playing, 1000, false, false);
        check(s.keys[16], "two fingers: focus (Shift) is held");
        check(s.keys[90], "two fingers: still shooting");
    }

    // 4. Bomb serial bump -> 3 samples of X (vk 88).
    {
        TouchController c;
        int frames = 0;
        for (int i = 0; i < 6; ++i) {
            c.controls(false, false, 1u, 0, 0, 0);  // serial is a monotonic counter: pass the current value
            if (c.sample(playing, 1000 + i, false, false).keys[88]) ++frames;
        }
        check(frames == 3, "bomb: X is held for exactly 3 samples");
    }

    // 5. Escape serial bump -> 3 samples of Esc (vk 27).
    {
        TouchController c;
        int frames = 0;
        for (int i = 0; i < 6; ++i) {
            c.controls(false, false, 0, 1u, 0, 0);
            if (c.sample(playing, 1000 + i, false, false).keys[27]) ++frames;
        }
        check(frames == 3, "escape: Esc is held for exactly 3 samples");
    }

    // 6. Menu state: a tap becomes confirm (Z) on release, no motion.
    {
        TouchState menu;
        menu.context = 0;
        menu.instance = 0;
        menu.ready = false;
        menu.x = menu.y = 0;
        menu.min_x = -184; menu.max_x = 184; menu.min_y = 32; menu.max_y = 432;
        TouchController c;
        c.pointer(0, 3, 0.5f, 0.5f, 2000, menu, false);
        c.pointer(2, 3, 0.5f, 0.5f, 2040, menu, false);       // short tap
        int frames = 0;
        for (int i = 0; i < 5; ++i) {
            c.controls(false, false, 0, 0, 0, 0);
            if (c.sample(menu, 2040 + i, false, false).keys[90]) ++frames;
        }
        check(frames == 3, "menu: a tap yields 3 samples of Z (confirm)");
        check(c.sample(menu, 2040, false, false).motion == 0, "menu: no movement");
    }

    // 7. Clamping: a huge drag stays inside the playfield bounds.
    {
        TouchController c;
        c.pointer(0, 1, 0.5f, 0.5f, 3000, playing, false);
        c.controls(true, false, 0, 0, 0, 0);
        c.pointer(1, 1, 5.f, 5.f, 3016, playing, false);      // way off-screen
        const auto s = c.sample(playing, 3016, false, false);
        check(std::fabs(s.x) <= 184.001f && std::fabs(s.y) <= 432.001f,
              "clamp: drag target stays inside the playfield");
    }

    std::printf("\n%s\n", failures == 0 ? "all touch checks passed" : "TOUCH CHECKS FAILED");
    return failures == 0 ? 0 : 1;
}
