# MNN 3.5.0 升级迁移指南

## 概述

本文档描述如何将 AIGallery-Rewrite 项目从当前 MNN 版本升级到 MNN 3.5.0。

## 升级前的准备

### 1. 备份当前版本

已备份到 `mnn35_backup/arm64-v8a/`:
- libMNN.so (44MB)
- libMNN_Express.so (19MB)
- libllm.so (23MB)
- liblocalai-jni.so (81KB)
- 其他库

### 2. 获取 MNN 3.5.0 资源

本项目已集成 MNN Android SDK 的资源：
- so库: `app/src/main/jniLibs_mnn35/arm64-v8a/`
- JNI源码: `app/src/main/cpp/mnn_llm.cpp`
- 头文件: `app/src/main/cpp/include/`

## 新增文件

### Kotlin 文件
1. `app/src/main/java/com/localai/server/engine/LlamaEngineMnn35.kt`
   - MNN 3.5.0 JNI 桥接类
   - 使用句柄模式管理 LLM 实例
   - 支持 Thinking 模式

2. `app/src/main/java/com/aigallery/rewrite/inference/MnnInferenceEngineV35.kt`
   - MNN 3.5.0 引擎实现
   - 支持 enableThinking 参数
   - 精确 token 计数

### C++ 文件
1. `app/src/main/cpp/mnn_llm.cpp`
   - MNN Android SDK 提供的 JNI 桥接源码

2. `app/src/main/cpp/include/llm/llm.hpp`
   - MNN LLM C++ API 头文件

### so 库
- `app/src/main/jniLibs_mnn35/arm64-v8a/` - MNN 3.5.0 优化版 so 库

## 编译 JNI 库

### 方法一：使用 Android Studio

1. 确保 Android Studio 安装了 NDK 27.2.12479018
2. 打开项目
3. Sync Gradle
4. Build > Build Modules > app

### 方法二：命令行编译

```bash
cd AIGallery-Rewrite
./gradlew assembleDebug
```

### 方法三：手动编译

```bash
cd AIGallery-Rewrite/app/src/main/cpp

# 创建 build 目录
mkdir -p build && cd build

# 配置 CMake
cmake .. \
    -DANDROID_NDK=${ANDROID_NDK_ROOT} \
    -DANDROID_ABI=arm64-v8a \
    -DCMAKE_BUILD_TYPE=Release

# 编译
make -j4

# 复制到 jniLibs 目录
cp liblocalai-jni-v35.so ../jniLibs_mnn35/arm64-v8a/
```

## 切换引擎版本

### 使用旧版 MNN (< 3.5)
```kotlin
val engine = InferenceEngineFactory.createEngine(EngineType.MNN, context)
```

### 使用 MNN 3.5.0
```kotlin
val engine = InferenceEngineFactory.createEngine(EngineType.MNN35, context)
```

### 启用 Thinking 模式
```kotlin
val config = InferenceConfig(
    enableThinking = true,
    maxLength = 512,
    temperature = 0.7f
)
val result = engine.infer("Explain quantum entanglement", config)
```

## API 变更

### InferenceConfig 新增字段
```kotlin
data class InferenceConfig(
    // ... 现有字段 ...
    /** 启用 Thinking 模式（Chain-of-Thought） */
    val enableThinking: Boolean = false
)
```

### EngineType 新增枚举
```kotlin
enum class EngineType {
    MNN,        // 旧版 MNN (< 3.5)
    MNN35,     // MNN 3.5.0+
    // ...
}
```

## MNN 3.5.0 新特性

### 1. Thinking 模式
支持 Chain-of-Thought 推理，模型会在回答前展示思考过程。

### 2. 20x Tokenizer 加速
使用二进制 tokenizer 格式 (.mtok)，加载速度大幅提升。

### 3. 优化的大小
| 对比项 | 旧版 | MNN 3.5.0 |
|--------|------|-----------|
| libMNN.so | 44MB | 2.3MB |
| libllm.so | 23MB | 954KB |
| 总计 | 89MB | 8.7MB |

### 4. QNN 后端支持
支持 Qualcomm NPU 加速。

### 5. Qwen3.5 支持
新增对 Qwen3.5 和 Qwen3.5-MoE 模型的支持。

## 已知限制

1. **需要重新编译 JNI**: 当前 MNN Android SDK 提供的 so 库不包含编译好的 JNI bridge
2. **API 不兼容**: LlamaEngineMnn35 使用句柄模式，与旧版单例模式不兼容
3. **Embedding 未支持**: MNN 3.5.0 当前版本可能不支持 generateEmbedding

## 故障排除

### 库加载失败
```
UnsatisfiedLinkError: dlopen failed: library "libllm.so" not found
```
**解决方案**: 确保 MNN 3.5.0 so 库在 `jniLibs_mnn35/arm64-v8a/` 目录中

### 模型加载失败
```
Failed to create LLM instance
```
**解决方案**: 检查 llm_config.json 是否存在且路径正确

### 编译失败
```
fatal error: llm/llm.hpp: No such file or directory
```
**解决方案**: 确保头文件在 `cpp/include/` 目录中

## 下一步

1. 编译 JNI 库 `liblocalai-jni-v35.so`
2. 在设备上测试
3. 更新 UI 以支持 Thinking 模式切换
4. 更新模型下载器以支持 Qwen3.5 模型

## 参考资源

- [MNN GitHub](https://github.com/alibaba/MNN)
- [MNN Android SDK](https://github.com/AdribMahmud101/mnn-android-sdk)
- [MNN LLM 文档](https://mnn-docs.readthedocs.io/en/latest/transformers/llm.html)
