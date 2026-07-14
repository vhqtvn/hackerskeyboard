cmake_minimum_required(VERSION 3.4.1)
project(vhkeyboard C)

# AYN Thor: uinput keyboard injector daemon.
# Built as an executable but named libkbdinject.so so the Android Gradle Plugin
# packages it as a native lib. With extractNativeLibs=true (useLegacyPackaging)
# it is unpacked to the app's nativeLibraryDir and can be exec'd (via `su`) at
# runtime. See cpp/kbdinject.c and docs/ayn-thor.md.
add_executable( kbdinject src/main/cpp/kbdinject.c )
set_target_properties( kbdinject PROPERTIES
                       PREFIX "lib"
                       OUTPUT_NAME "kbdinject"
                       SUFFIX ".so" )
