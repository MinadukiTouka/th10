/* SoftFloat build wrapper.
 *
 * Upstream compiles rebuild/third_party/softfloat.c as C with
 * SOFTFLOAT_FAST_INT64 and INLINE_LEVEL=5 (see the Switch CMakeLists). ndk-build
 * cannot set per-file defines, so the defines go here and the amalgamation is
 * included instead of being listed as a source file.
 */
#define SOFTFLOAT_FAST_INT64 1
#define INLINE_LEVEL 5
#include "../rebuild/third_party/softfloat.c"
