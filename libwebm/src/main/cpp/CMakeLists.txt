cmake_minimum_required(VERSION 3.22)
project(hortaywebm C)

set(ABI ${ANDROID_ABI})
set(FF ${CMAKE_SOURCE_DIR}/../jniLibs/${ABI})

add_library(hortaywebm SHARED webm_alpha_decoder.c)
target_include_directories(hortaywebm PRIVATE ${CMAKE_SOURCE_DIR}/include-${ABI})

# Static ffmpeg archives. Declare each as an IMPORTED target; link order matters for static
# libs (dependents before dependencies): avformat -> avcodec -> swscale -> swresample -> avutil.
foreach(lib avformat avcodec swscale swresample avutil)
  add_library(${lib} STATIC IMPORTED)
  set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${FF}/lib${lib}.a)
endforeach()

find_library(log-lib log)
target_link_libraries(hortaywebm
  avformat avcodec swscale swresample avutil
  ${log-lib} z)
