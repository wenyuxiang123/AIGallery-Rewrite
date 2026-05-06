# MNN 3.5.0 升级完成报告

## 执行日期
2025-05-06

## 执行摘要

已完成 AIGallery-Rewrite 项目的 MNN 3.5.0 升级准备工作。由于JNI接口不兼容，无法直接替换so库，而是添加了完整的MNN 3.5.0兼容基础设施。

## 完成的工作

### 1. 分析与调研 ✅
- [x] 分析当前项目的MNN依赖结构
- [x] 下载并分析MNN 3.5.0 MnnLlmChat APK
- [x] 分析MNN Android SDK的API和结构
- [x] 对比JNI接口兼容性
- [x] 生成详细分析报告

### 2. 代码准备 ✅
- [x] 创建 `LlamaEngineMnn35.kt` - MNN 3.5.0 JNI桥接类
- [x] 创建 `MnnInferenceEngineV35.kt` - MNN 3.5.0引擎实现
- [x] 更新 `InferenceEngine.kt` - 添加enableThinking和MNN35类型
- [x] 创建 `CMakeLists.txt` - JNI编译配置

### 3. 资源准备 ✅
- [x] 添加MNN Android SDK的JNI源码 (mnn_llm.cpp)
- [x] 添加MNN 3.5.0头文件
- [x] 添加MNN 3.5.0优化版so库 (8.7MB)
- [x] 备份当前MNN库到 mnn35_backup/

### 4. 文档 ✅
- [x] 创建 `MNN_3.5.0_MIGRATION_GUIDE.md` - 迁移指南
- [x] 创建 `MNN_3.5.0_UPGRADE_REPORT.md` - 分析报告

### 5. Git提交 ✅
- [x] Commit: `feat: add MNN 3.5.0 support infrastructure`
- [x] Push到 origin/main
- [x] CI构建已触发

## 新增文件列表

```
MNN_3.5.0_MIGRATION_GUIDE.md    # 迁移指南
app/src/main/cpp/
├── CMakeLists.txt              # JNI编译配置
├── mnn_llm.cpp                 # MNN Android SDK JNI源码
└── include/
    ├── llm/llm.hpp            # MNN LLM API头文件
    └── MNN/                   # MNN核心头文件
        ├── AutoTime.hpp
        ├── ErrorCode.hpp
        ├── HalideRuntime.h
        ├── ImageProcess.hpp
        ├── Interpreter.hpp
        ├── MNNDefine.h
        ├── MNNForwardType.h
        ├── MNNSharedContext.h
        ├── Matrix.h
        ├── Rect.h
        ├── Tensor.hpp
        ├── expr/
        │   ├── Executor.hpp
        │   ├── ExecutorScope.hpp
        │   ├── Expr.hpp
        │   ├── ExprCreator.hpp
        │   ├── MathOp.hpp
        │   ├── Module.hpp
        │   ├── NeuralNetWorkOp.hpp
        │   ├── Optimizer.hpp
        │   └── Scope.hpp
        └── plugin/
            ├── PluginContext.hpp
            ├── PluginKernel.hpp
            └── PluginShapeInference.hpp
app/src/main/java/com/localai/server/engine/
└── LlamaEngineMnn35.kt        # MNN 3.5.0 JNI桥接
app/src/main/java/com/aigallery/rewrite/inference/
├── MnnInferenceEngineV35.kt    # MNN 3.5.0引擎
└── InferenceEngine.kt          # 更新: enableThinking, MNN35
app/src/main/jniLibs_mnn35/arm64-v8a/
├── libMNN.so (2.3MB)
├── libllm.so (954KB)
├── libMNN_Express.so (734KB)
├── libMNN_CL.so (2.1MB)
├── libMNN_Vulkan.so (721KB)
├── libmnncore.so (23KB)
├── libMNNAudio.so (49KB)
├── libMNNOpenCV.so (259KB)
└── libc++_shared.so (1.8MB)
mnn35_backup/arm64-v8a/        # 当前MNN库备份
├── libMNN.so (44MB)
├── libllm.so (23MB)
├── libMNN_Express.so (19MB)
└── liblocalai-jni.so (81KB)
```

## 重要发现

### 1. JNI接口不兼容
- **当前项目**: 使用 `com.localai.server.engine.LlamaEngine` 类，方法签名如 `nativeLoadModel`, `nativeGenerate`
- **MNN 3.5.0**: 使用 `com.alibaba.mnnllm.android.llm.LlmSession` 类，或 `MNN::Transformer::Llm` C++ API
- **结论**: 无法直接替换so库，需要重写JNI桥接

### 2. so库大小差异巨大
| 组件 | 当前版本 | MNN 3.5.0 |
|------|----------|-----------|
| libMNN.so | 44MB | 2.3MB |
| libllm.so | 23MB | 954KB |
| libMNN_Express.so | 19MB | 734KB |
| **总计** | **89MB** | **8.7MB** |

MNN 3.5.0的库体积减少了**90%**！

### 3. API变化
- 从单例模式改为句柄模式
- 采样参数从temperature/topK/topP改为JSON配置
- 新增Thinking模式支持
- 新增精确token计数

## CI构建状态

- **URL**: https://github.com/wenyuxiang123/AIGallery-Rewrite/actions/runs/25445217816
- **状态**: in_progress
- **注意**: 初始的Kotlin代码编译应通过，但完整的JNI编译需要Android NDK环境

## 待完成工作

### 需要在本地完成
1. **编译JNI库**: 需要Android NDK 27.2.12479018来编译 liblocalai-jni-v35.so
2. **复制JNI库**: 将编译后的so复制到 jniLibs_mnn35/arm64-v8a/
3. **更新build.gradle**: 添加MNN 3.5.0的jniLibs配置
4. **测试**: 在真机上测试MNN 3.5.0推理

### 编译JNI库的步骤
```bash
# 1. 确保安装了Android NDK 27.2.12479018
export ANDROID_NDK=$HOME/android-ndk-r27b

# 2. 在项目目录编译
cd AIGallery-Rewrite
./gradlew assembleDebug

# 3. 或者手动cmake编译
cd app/src/main/cpp
mkdir -p build && cd build
cmake .. -DANDROID_NDK=$ANDROID_NDK -DANDROID_ABI=arm64-v8a
make

# 4. 复制so到正确位置
cp liblocalai-jni-v35.so ../jniLibs_mnn35/arm64-v8a/
```

## 升级后的使用方法

```kotlin
// 使用MNN 3.5.0引擎
val engine = InferenceEngineFactory.createEngine(EngineType.MNN35, context)

// 配置（可选：启用Thinking模式）
val config = InferenceConfig(
    enableThinking = true,
    maxLength = 512,
    temperature = 0.7f
)

// 推理
val result = engine.infer("Explain quantum entanglement", config)
```

## MNN 3.5.0新特性

1. **Thinking模式**: 支持Chain-of-Thought推理
2. **20x Tokenizer加速**: 二进制tokenizer格式(.mtok)
3. **90%体积缩减**: 从89MB降至8.7MB
4. **Qwen3.5支持**: 原生支持Qwen3.5和Qwen3.5-MoE
5. **QNN后端**: Qualcomm NPU加速支持
6. **TurboQuant**: 新KV Cache量化方案(TQ3/TQ4)
7. **Linear Attention**: 完整实现

## 风险评估

| 风险 | 等级 | 说明 | 缓解措施 |
|------|------|------|----------|
| JNI接口不兼容 | 🔴高 | 需要重写JNI桥接 | 已添加LlamaEngineMnn35.kt |
| 编译环境依赖 | 🟡中 | 需要NDK编译JNI | 提供编译指南 |
| API行为变化 | 🟡中 | 采样方式改变 | 提供迁移文档 |
| 模型兼容性 | 🟢低 | MNN模型格式兼容 | 无需转换 |

## 建议的后续行动

1. **短期**: 
   - 克隆仓库到本地环境
   - 编译JNI库
   - 在设备上测试基本推理

2. **中期**:
   - 实现Thinking模式的UI切换
   - 更新模型下载器支持Qwen3.5
   - 进行性能基准测试

3. **长期**:
   - 废弃旧版MNN引擎
   - 完全迁移到MNN 3.5.0
   - 利用新特性优化用户体验
