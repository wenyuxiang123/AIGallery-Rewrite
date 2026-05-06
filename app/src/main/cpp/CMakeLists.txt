# MNN 3.5.0 JNI 桥接 CMakeLists.txt
# 
# 此文件用于编译 liblocalai-jni-v35.so
# 编译后的库将使用 MNN Android SDK 的接口

cmake_minimum_required(VERSION 3.22.1)
project("localai-jni-v35")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Android NDK 设置
if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK not defined")
endif()

# MNN 3.5.0 头文件路径
set(MNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../cpp")
set(MNN_INCLUDE_DIR "${MNN_ROOT}/include")

include_directories(
    ${ANDROID_NDK}/sources/cxx-stl/llvm-libc++/include
    ${ANDROID_NDK}/sysroot/usr/include
    ${ANDROID_NDK}/sysroot/usr/include/aarch64-linux-android
    ${MNN_INCLUDE_DIR}
)

# MNN 3.5.0 库文件目录
set(MNN_LIB_DIR "${CMAKE_SOURCE_DIR}/../jniLibs_mnn35/${ANDROID_ABI}")

# 源文件
set(SOURCES
    ../cpp/mnn_llm.cpp
)

# 创建库
add_library(localai-jni-v35 SHARED ${SOURCES})

# 链接 MNN 3.5.0 库
target_link_libraries(localai-jni-v35
    mnncore
    MNN
    MNN_Express
    llm
    ${ANDROID_NDK}/sources/cxx-stl/llvm-libc++/libs/${ANDROID_ABI}/libc++_shared.so
)

# 设置输出名称
set_target_properties(localai-jni-v35 PROPERTIES
    OUTPUT_NAME "localai-jni-v35"
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)
