# AIGallery-Rewrite

基于 Kotlin + Jetpack Compose + Hilt + Room + DataStore 的现代化 AI 助手应用

## 项目概述

AIGallery-Rewrite 是一个重构自 Google AI Edge Gallery 的现代化 Android 应用，采用最新的 Android 开发技术和 Clean Architecture 架构。

## 技术栈

- **语言**: Kotlin 1.9.22
- **UI框架**: Jetpack Compose + Material 3
- **架构**: MVVM + Clean Architecture + Repository 模式
- **依赖注入**: Hilt
- **本地存储**: Room Database + Proto DataStore
- **后台任务**: WorkManager
- **目标SDK**: 34 | 最小SDK: 29

## 项目结构

```
com.aigallery.rewrite/
├── data/                          # 数据层
│   ├── local/
│   │   ├── dao/                   # Room DAOs
│   │   ├── datastore/             # DataStore
│   │   └── entity/                # Room 实体
│   └── repository/                # Repository 实现
├── di/                            # Hilt 依赖注入模块
├── domain/                        # 领域层
│   └── model/                     # 领域模型
├── ui/                            # UI层
│   ├── components/                # 通用组件
│   ├── navigation/                # 导航
│   ├── screens/                   # 页面
│   │   ├── home/                  # 首页
│   │   ├── llmchat/               # LLM对话
│   │   ├── memory/                # 记忆中心
│   │   ├── modelmanager/          # 模型管理
│   │   ├── customtasks/           # 自定义任务
│   │   ├── singleturn/            # 单轮任务
│   │   └── settings/              # 设置
│   └── theme/                     # 主题
└── util/                          # 工具类
```

## 核心功能

### 1. 模型管理模块
- 模型列表展示（10+ 开源模型）
- 模型下载、删除管理
- 下载进度跟踪
- **国内镜像支持**: ModelScope 镜像加速

支持的模型:
- Llama 3 8B/70B
- Qwen 2 7B/14B
- Gemma 2 9B/27B
- Mistral 7B/8x7B
- Phi-3 7B/14B

### 2. LLM 聊天模块
- 多轮对话
- 流式输出
- 文本、图片、语音输入
- 聊天历史记录

### 3. 五层记忆系统 (核心创新)
```
五层记忆架构:
├── 1. 工作记忆 (Working Memory)
│   └── 当前会话上下文，自动清空
├── 2. 短期记忆 (Short-term Memory)
│   └── 最近N轮对话，滑动窗口保留
├── 3. 长期记忆 (Long-term Memory)
│   └── 向量数据库存储，RAG检索召回
├── 4. 知识库 (Knowledge Base)
│   └── 用户上传文档、网页内容索引
└── 5. 角色记忆 (Persona Memory)
    └── 系统角色设定、人设、习惯偏好
```

### 4. 自定义任务模块
- Agent Chat: 智能体对话 + 工具调用
- Mobile Actions: 手机控制（WiFi、蓝牙、手电筒、短信等）
- Example Custom Task: 示例任务模板
- Tiny Garden: 浏览器自动化

### 5. 单轮任务模块
- 写作助手
- 翻译
- 摘要
- 改写
- 分析
- 头脑风暴

## 构建要求

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android SDK 34
- Gradle 8.2

## 构建步骤

1. 克隆项目
```bash
git clone <repository-url>
cd AIGallery-Rewrite
```

2. 同步 Gradle
```bash
./gradlew --stop 2>/dev/null || true
./gradlew clean
./gradlew assembleDebug
```

3. 运行应用
```bash
./gradlew installDebug
```

## 开发指南

### 添加新模块

1. 在 `domain/model/` 添加领域模型
2. 在 `data/local/entity/` 添加 Room 实体
3. 在 `data/local/dao/` 添加 DAO
4. 在 `data/repository/` 添加 Repository
5. 在 `ui/screens/` 添加 Screen 和 ViewModel
6. 在 `ui/navigation/Screen.kt` 添加路由
7. 在 `di/AppModule.kt` 添加依赖注入

### 内存系统使用

```kotlin
// 注入 MemoryRepository
@Inject
lateinit var memoryRepository: MemoryRepository

// 添加短期记忆
memoryRepository.addShortTermMemory("用户询问了天气", 0.8f)

// 添加长期记忆
memoryRepository.addLongTermMemory("用户喜欢喝咖啡", listOf("coffee", "preference"))

// 检索相关记忆
val memories = memoryRepository.retrieveAllRelevantMemories(
    context = "用户刚才问的问题",
    config = MemoryConfig()
)
```

## License

MIT License

## 致谢

- 原始项目参考: Google AI Edge Gallery
- UI框架: Jetpack Compose
- 设计系统: Material Design 3
