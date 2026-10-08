cmake_minimum_required(VERSION 3.4.1)
project(vhkeyboard C CXX)

# AYN Thor: uinput keyboard injector daemon.
# Built as an executable but named libkbdinject.so so the Android Gradle Plugin
# packages it as a native lib. With extractNativeLibs=true (useLegacyPackaging)
# it is unpacked to the app's nativeLibraryDir and can be exec'd (via `su`) at
# runtime. See cpp/kbdinject.c and docs/ayn-thor.md.
# Only the `thor` product flavor sets BUILD_KBDINJECT=ON, so other devices ship no
# root/injection binary at all.
option( BUILD_KBDINJECT "Build the AYN Thor uinput injector daemon" OFF )
if( BUILD_KBDINJECT )
  add_executable( kbdinject src/main/cpp/kbdinject.c )
  set_target_properties( kbdinject PROPERTIES
                         PREFIX "lib"
                         OUTPUT_NAME "kbdinject"
                         SUFFIX ".so" )
endif()

# Vietnamese input: bundled Unikey engine + JNI bridge -> libunikey.so
file(GLOB UKENGINE_SRC src/main/cpp/ukengine/*.cpp)
add_library( unikey SHARED src/main/cpp/unikey_jni.cpp ${UKENGINE_SRC} )
target_include_directories( unikey PRIVATE src/main/cpp/ukengine )
