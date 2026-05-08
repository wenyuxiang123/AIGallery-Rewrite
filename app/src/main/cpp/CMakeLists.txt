cmake_minimum_required(VERSION 3.22.1)
project("localai-jni")

# 源文件
add_library(${CMAKE_PROJECT_NAME} SHARED
    mnn_llm.cpp
)

# 编译选项
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -O2 -fPIC)

# 16KB page size support (Android 15+)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")

# MNN头文件
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# 预编译so路径
set(JNI_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a")

# 声明预编译依赖库 (MNN_SEP_BUILD=OFF: 所有代码合并到libMNN.so)
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libMNN.so")

# 链接：MNN_SEP_BUILD=OFF时只需要链接libMNN.so (已包含Express和LLM代码)
target_link_libraries(${CMAKE_PROJECT_NAME}
    MNN
    android
    log
)
