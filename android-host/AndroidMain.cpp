// Touhou 10 for Android: native entry point (libmain.so, SDL_main).
//
// This is the TH10 Android counterpart of th10-port/src/main.cpp (the Switch /
// Linux host). Everything below the host is unchanged upstream code:
//
//   files        sd=Download/th10 (or app external storage), saves in a sibling
//                "th10-save" folder                       -> src/FileHost.cpp
//   input        Android touch -> portable/input/TouchController -> analog motion
//   graphics     GLES3 on an SDL2 window                     -> portable/sdl/Renderer.cpp
//   audio        miniaudio mixer -> SDL queued audio (AAudio) -> src/AudioHost.cpp
//   fonts        FreeType, msgothic.ttc or the Android system CJK font
//                                                            -> src/FontHost.cpp
//
// What is Android-specific here:
//   * the data/save search order (mirrored by LauncherActivity.java),
//   * finger bookkeeping for the gesture policy: hold = shot, two fingers = focus,
//     release grace so a quick re-touch does not stop shooting,
//   * a bomb zone in the 4:3 pillarbox bar (right side only, like the TH08 port),
//   * the overlay Esc / quit buttons (Java) and Back -> Escape,
//   * stop stepping while the activity is in the background.
#include "../platform/Application.hpp"
#include "../src/Platform.hpp"
#include "../portable/sdl/FrameCadence.hpp"
#include "../portable/sdl/Renderer.hpp"
#include "../portable/input/TouchController.hpp"

#include <SDL.h>
#include <GLES3/gl3.h>
#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <set>
#include <string>
#include <vector>

using namespace th10;
using namespace th10::browser;

extern "C" {
FileSystem* files_create();void files_destroy(FileSystem*);u32 files_attach(FileSystem*,const char*);
Input* input_create();void input_destroy(Input*);GameState* game_state_create(Input*,u32);void game_state_destroy(GameState*);
GraphicsDevice* graphics_create(const GraphicsPresentation*,u32);void graphics_destroy(GraphicsDevice*);
AnimationEngine* animation_engine_create(FileSystem*,GraphicsDevice*,Rng*,Rng*,float*);void animation_engine_destroy(AnimationEngine*);
Fonts* fonts_create(GraphicsDevice*,Rng*,u32);void fonts_destroy(Fonts*);Audio* audio_create(FileSystem*);void audio_destroy(Audio*);
ScreenEffects* effects_create(AnimationEngine*,const u32*,const u32*);void effects_destroy(ScreenEffects*);
Application* application_create(FileSystem*,Input*,GameState*,AnimationEngine*,Fonts*,Audio*,ScreenEffects*);void application_destroy(Application*);
void application_touch_state(Application*,u32*);void sdl_files_root(u32);
void sdl_audio_shutdown();void sdl_fonts_shutdown();void sdl_shutdown();
void sdl_audio_pump();void sdl_audio_pause(u32);
touhou::sdl::Renderer* sdl_renderer();
}

namespace {
struct Session {
    FileSystem* files=nullptr;Input* input=nullptr;GameState* state=nullptr;GraphicsDevice* device=nullptr;
    AnimationEngine* animation=nullptr;Fonts* fonts=nullptr;Audio* audio=nullptr;ScreenEffects* effects=nullptr;Application* app=nullptr;
    Rng random{},visual{};float rate=1;u32 quitting=0;
    ~Session(){if(app)application_destroy(app);if(effects)effects_destroy(effects);if(audio)audio_destroy(audio);sdl_audio_shutdown();if(fonts)fonts_destroy(fonts);sdl_fonts_shutdown();if(animation)animation_engine_destroy(animation);if(device)graphics_destroy(device);if(state)game_state_destroy(state);if(input)input_destroy(input);if(files)files_destroy(files);sdl_shutdown();}
};
std::unique_ptr<Session> session;
std::string failure;

// Frame timing for th10-android.log, averaged over 600 played frames.
struct FrameTiming {
    double step=0,present=0,since=0;u32 frames=0;
    void add(double s,double p){step+=s;present+=p;if(!frames++)since=host::seconds()-s-p;}
    bool due() const {return frames>=600;}
    void report(){const double elapsed=host::seconds()-since;
        host::log("timing (ms/frame): step %.2f, present %.2f, fps %.1f",step*1000/frames,present*1000/frames,elapsed>0?frames/elapsed:0.);
        *this={};}
} timing;

Application* open_game(u32 seed){
    session=std::make_unique<Session>();auto& s=*session;sdl_files_root(0);u32 rng[]{seed,0};std::memcpy(&s.random,rng,8);std::memcpy(&s.visual,rng,8);
    s.files=files_create();if(!s.files||!files_attach(s.files,"th10.dat")){failure="Unable to open th10.dat";return nullptr;}
    const u32 parameters[]{640,480,22,1,0,0,1,0,1,1,80,0,0,0};s.input=input_create();s.state=game_state_create(s.input,0);
    s.device=graphics_create(reinterpret_cast<const GraphicsPresentation*>(parameters),0x40);if(!s.device){failure="Unable to create the OpenGL ES renderer";return nullptr;}
    s.animation=animation_engine_create(s.files,s.device,&s.random,&s.visual,&s.rate);s.fonts=fonts_create(s.device,&s.random,0);s.audio=audio_create(s.files);s.effects=effects_create(s.animation,&s.quitting,nullptr);
    s.app=application_create(s.files,s.input,s.state,s.animation,s.fonts,s.audio,s.effects);if(!s.app)failure="Unable to start the game";return s.app;
}

struct Key {const char* code;const char* sdl;u32 scan,vk;bool hosted=false;SDL_Scancode native=SDL_SCANCODE_UNKNOWN;};
#include "../portable/input/KeyboardMap.inc"
Key* key(const char* code){for(auto& k:keyboard_map)if(!std::strcmp(k.code,code))return &k;return nullptr;}
void host_key(const char* code,bool down){if(auto* k=key(code))k->hosted=down;}

touhou::input::TouchController gestures;
std::set<int> fingers;              // currently down pointer ids
std::uint32_t bomb_serial=0, escape_serial=0;
double fire_until=0;                // release grace, seconds on host::seconds()

touhou::input::TouchState touch_state(){
    touhou::input::TouchState s;if(!session||!session->app)return s;u32 raw[8];application_touch_state(session->app,raw);float values[5];std::memcpy(values,raw+3,20);
    s.context=int(raw[0]);s.instance=int(raw[1]);s.ready=raw[2]!=0;s.x=values[0];s.y=values[1];s.fast=values[2];s.slow=values[3];
    // Playfield bounds of the 640x480 frame; keeps the player sprite on screen.
    s.min_x=-184;s.max_x=184;s.min_y=32;s.max_y=432;return s;
}
void cancel_touch(){gestures.cancel();fingers.clear();if(session&&session->app&&session->app->world)session->app->world->motion.target(0,0,0);}
void key(InputSnapshot& s,u32 scan,u32 vk){s.scan_keys[scan]=128;s.virtual_keys[vk]=128;if(vk>=160&&vk<=165)s.virtual_keys[16+(vk-160)/2]=128;}
}

// Called from GameActivity (the ≡ button and the Back key).
extern "C" JNIEXPORT void JNICALL Java_com_zun_th10_GameActivity_nativeTapEsc(JNIEnv*,jclass){
    ++escape_serial;
}

namespace {
// Ask the activity to close; the game loop has already returned.
void android_finish(){
    JNIEnv* env=static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
    jobject activity=static_cast<jobject>(SDL_AndroidGetActivity());
    if(!env||!activity)return;
    jclass cls=env->GetObjectClass(activity);
    jmethodID method=env->GetMethodID(cls,"requestFinish","()V");
    if(method)env->CallVoidMethod(activity,method);
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(activity);
}

// Surface a startup failure on the device screen (Toast) as well as in the log.
void android_show_error(const std::string& message){
    JNIEnv* env=static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
    jobject activity=static_cast<jobject>(SDL_AndroidGetActivity());
    if(!env||!activity)return;
    jclass cls=env->GetObjectClass(activity);
    jmethodID method=env->GetMethodID(cls,"showErrorAndFinish","(Ljava/lang/String;)V");
    if(method){
        jstring text=env->NewStringUTF(message.c_str());
        env->CallVoidMethod(activity,method,text);
        if(text)env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(activity);
}

void fatal(const std::string& message){
    host::log("fatal: %s",message.c_str());
    session.reset();
    android_show_error(message);
    SDL_Quit();
}

// One-shot environment dump. The first device log should answer "which data,
// which GPU, which audio driver, which font" without needing another build.
void log_diagnostics(){
    const auto& p=host::paths();
    struct Entry {const char* name;bool save;};
    const Entry entries[]={{"th10.dat",false},{"thbgm.dat",false},{"msgothic.ttc",false},
                           {"scoreth10.dat",true},{"th10.cfg",true}};
    for(const Entry& e:entries){
        const auto path=e.save?p.save_file(e.name):p.data_file(e.name);
        FILE* f=std::fopen(path.c_str(),"rb");
        if(!f){host::log("file: %-13s missing (%s)",e.name,e.save?"save":"data");continue;}
        std::fseek(f,0,SEEK_END);const long size=std::ftell(f);std::fclose(f);
        host::log("file: %-13s %ld bytes (%s)",e.name,size,e.save?"save":"data");
    }
    if(const auto* vendor=reinterpret_cast<const char*>(glGetString(GL_VENDOR)))
        host::log("gl vendor: %s",vendor);
    if(const auto* renderer=reinterpret_cast<const char*>(glGetString(GL_RENDERER)))
        host::log("gl renderer: %s",renderer);
    if(const auto* version=reinterpret_cast<const char*>(glGetString(GL_VERSION)))
        host::log("gl version: %s",version);
    // SDL audio is initialised lazily on the first buffer, so the driver is not
    // known yet here; AudioHost logs it when the device opens.
    int dw=0,dh=0;
    if(auto* r=sdl_renderer())SDL_GL_GetDrawableSize(r->sdl_window(),&dw,&dh);
    host::log("drawable: %dx%d",dw,dh);
}

// Touch coordinates arrive normalised; the game wants 640x480 picture space,
// which is pillarboxed inside the drawable (Renderer::picture).
void touch_event(const SDL_Event& e){
    auto* r=sdl_renderer();if(!r)return;const auto& p=r->picture;if(p.width<=0||p.height<=0)return;
    const float x=(e.tfinger.x*p.drawable_width-p.x)/p.width,y=(e.tfinger.y*p.drawable_height-p.y)/p.height;
    const bool down=e.type==SDL_FINGERDOWN, up=e.type==SDL_FINGERUP;
    const std::uint64_t now=SDL_GetTicks();
    if(down)fingers.insert(int(e.tfinger.fingerId));else if(up)fingers.erase(int(e.tfinger.fingerId));
    if(!fingers.empty())fire_until=host::seconds()+.18;   // 180 ms of shot after the last finger lifts
    // Bomb zone: the black bar beside the 4:3 picture (right side only, so a
    // left-thumb drag off the picture edge does not bomb by accident).
    if(down&&p.width<p.drawable_width&&x>1.f){++bomb_serial;return;}
    if(down&&x<0.f)return;                                // left bar: drag space, not a control
    const int type=down?0:up?2:1;
    if(type==0&&(x<0||x>1||y<0||y>1))return;
    const bool key_slow=session&&session->input&&session->input->snapshot.virtual_keys[16]!=0;
    gestures.pointer(type,int(e.tfinger.fingerId),std::clamp(x,0.f,1.f),std::clamp(y,0.f,1.f),
                     now,touch_state(),key_slow);
}

// Builds the per-frame InputSnapshot: physical keyboard, then the touch gesture
// policy (which also supplies shot/focus/bomb/escape).
void native_input(Application* app){
    const auto now=host::seconds();
    const bool firing=!fingers.empty()||now<fire_until;
    gestures.controls(firing,fingers.size()>1,bomb_serial,escape_serial,0,0);

    auto& snapshot=app->input.snapshot;std::memset(&snapshot,0,sizeof(snapshot));snapshot.focused=1;
    const u8* physical=SDL_GetKeyboardState(nullptr);
    for(const auto& k:keyboard_map)if(k.hosted||(k.native!=SDL_SCANCODE_UNKNOWN&&physical[k.native]))key(snapshot,k.scan,k.vk);
    const auto sample=gestures.sample(touch_state(),SDL_GetTicks(),
        snapshot.virtual_keys[16]!=0,snapshot.virtual_keys[37]||snapshot.virtual_keys[38]||snapshot.virtual_keys[39]||snapshot.virtual_keys[40]);
    for(const auto& k:keyboard_map)if(sample.keys[k.vk]||(k.vk>=160&&k.vk<=165&&sample.keys[16+(k.vk-160)/2]))key(snapshot,k.scan,k.vk);
    if(app->world)app->world->motion.target(sample.motion,sample.x,sample.y);
    // Touch diagnostics: one line per second while a finger is down. Kept in the
    // release build on purpose - it is the only way to see the mapped state on a
    // device without a debugger, and it costs one branch per frame.
    if(!fingers.empty()){
        static u32 touch_frames=0;
        if((++touch_frames%60)==0){
            const auto ts=touch_state();
            host::log("touch: ctx=%d ready=%d inst=%d pos=%.1f,%.1f motion=%d target=%.1f,%.1f",
                ts.context,int(ts.ready),ts.instance,ts.x,ts.y,sample.motion,sample.x,sample.y);
        }
    }
}
}

// The game's monotonic() clock: seconds of running loop (TimeHost.cpp).
extern double th10_loop_elapsed;

int main(int,char**){
    host::locate_data(0,nullptr);
    host::log("th10-android: data %s",host::paths().data.c_str());
    host::log("th10-android: save %s",host::paths().save.c_str());
    if(!host::exists(host::paths().data_file("th10.dat"))){fatal("th10.dat not found - put it in Download/th10");return 1;}
    for(auto& k:keyboard_map)k.native=SDL_GetScancodeFromName(k.sdl);

    u32 seed=u32(std::time(nullptr));
    host::log("th10-android: starting (seed %u)",seed);
    Application* app=open_game(seed);
    if(!app){fatal(failure);return 1;}
    log_diagnostics();

    constexpr double interval=touhou::sdl::FrameCadence::interval;
    touhou::sdl::FrameCadence cadence;
    double previous=host::seconds(),audio_remainder=0;
    bool running=true,foreground=true;int result=0;
    while(running&&!result){
        SDL_Event e;while(SDL_PollEvent(&e)){
            if(e.type==SDL_QUIT)running=false;
            else if(e.type==SDL_FINGERDOWN||e.type==SDL_FINGERUP||e.type==SDL_FINGERMOTION)touch_event(e);
            else if(e.type==SDL_APP_WILLENTERBACKGROUND){foreground=false;cancel_touch();sdl_audio_pause(1);}
            else if(e.type==SDL_APP_DIDENTERFOREGROUND){foreground=true;previous=host::seconds();cadence.reset();sdl_audio_pause(0);}
            else if(e.type==SDL_WINDOWEVENT&&e.window.event==SDL_WINDOWEVENT_FOCUS_LOST){for(auto& k:keyboard_map)k.hosted=false;cancel_touch();}
        }
        if(!foreground){SDL_Delay(16);continue;}

        const double now=host::seconds(),delta=std::clamp(now-previous,0.,.1);previous=now;
        th10_loop_elapsed+=delta;audio_remainder+=delta*1000;const auto milliseconds=u32(std::floor(audio_remainder));audio_remainder-=milliseconds;
        app->audio.advance(milliseconds);
        auto* renderer=sdl_renderer();
        if(app->world&&app->world->loading){
            const double deadline=host::seconds()+.012;
            do{app->world->advance_loading_step();}while(app->world->loading&&host::seconds()<deadline);
            app->sync_views();
            if(app->world->loading){app->engine.device.present_frame();sdl_audio_pump();cadence.reset();continue;}
        }
        // The game's logic is 60 Hz. On a 90/120 Hz panel this returns 0 on the
        // frames in between: skip the tick instead of running the game fast.
        const u32 ticks=cadence.advance(delta);
        if(!ticks){SDL_Delay(1);continue;}
        if(renderer)renderer->defer=true;
        const double started=host::seconds();
        for(u32 n=0;n<ticks&&!result;++n){native_input(app);result=app->step(true);}
        const double stepped=host::seconds();
        if(renderer){renderer->commit();renderer->defer=false;}
        sdl_audio_pump();
        timing.add(stepped-started,host::seconds()-stepped);if(timing.due())timing.report();
    }
    if(result==2){fatal("The game stopped with an error ("+std::to_string(app->error)+")");return 1;}
    host::log("loop exit: result=%d running=%d quitting=%u",result,int(running),session?session->quitting:0u);
    session.reset();
    android_finish();
    SDL_Quit();
    return 0;
}
