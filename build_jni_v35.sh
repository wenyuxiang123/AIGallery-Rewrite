#!/bin/bash
# MNN 3.5.0 JNI 桥接库编译脚本
# 需要 Android NDK 环境
# 用法: ./build_jni_v35.sh

set -e

# 检查 NDK
if [ -z "$ANDROID_NDK" ]; then
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        export ANDROID_NDK="$HOME/Android/Sdk/ndk/$(ls $HOME/Android/Sdk/ndk/ | tail -1)"
    elif [ -d "/opt/android-ndk" ]; then
        export ANDROID_NDK="/opt/android-ndk"
    else
        echo "错误: 未找到 Android NDK"
        echo "请设置 ANDROID_NDK 环境变量或安装 NDK"
        exit 1
    fi
fi

echo "使用 NDK: $ANDROID_NDK"

# 项目路径
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

# 创建构建目录
BUILD_DIR="app/src/main/cpp/build_jni"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# CMake 配置
cmake -B "$BUILD_DIR" \
    -DANDROID_NDK="$ANDROID_NDK" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-21 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=17 \
    app/src/main/cpp

# 编译
cmake --build "$BUILD_DIR" --target localai-jni-v35 -j$(nproc)

# 复制到 jniLibs
cp "$BUILD_DIR/liblocalai-jni-v35.so" app/src/main/jniLibs/arm64-v8a/

echo "编译完成! liblocalai-jni-v35.so 已复制到 jniLibs"
