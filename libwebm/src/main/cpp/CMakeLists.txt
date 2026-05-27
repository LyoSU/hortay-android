cmake_minimum_required(VERSION 3.22)
project(hortaywebm C)

set(ABI ${ANDROID_ABI})
set(FF ${CMAKE_SOURCE_DIR}/../jniLibs/${ABI})

add_library(hortaywebm SHARED webm_alpha_decoder.c)
target_include_directories(hortaywebm PRIVATE ${CMAKE_SOURCE_DIR}/include-${ABI})

# Static archives. Declare each as an IMPORTED target; link order matters for static libs
# (dependents before dependencies): avformat -> avcodec -> {swscale,swresample} -> avutil, and
# libvpx after avcodec (avcodec's libvpx_vp9 decoder references it).
foreach(lib avformat avcodec swscale swresample avutil vpx)
  add_library(${lib} STATIC IMPORTED)
  set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${FF}/lib${lib}.a)
endforeach()

find_library(log-lib log)
# --start-group/--end-group makes static symbol resolution order-insensitive across the ffmpeg
# <-> libvpx cycle, so we don't have to hand-tune the link order.
target_link_libraries(hortaywebm
  -Wl,--start-group avformat avcodec swscale swresample avutil vpx -Wl,--end-group
  ${log-lib} z)

# Android 15 / 16 KB page support: align the .so's LOAD segments to 16 KB so the loader
# accepts it on 16 KB-page devices (otherwise System.loadLibrary fails there).
target_link_options(hortaywebm PRIVATE "-Wl,-z,max-page-size=16384")
