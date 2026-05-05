package com.aigallery.rewrite.di

import com.aigallery.rewrite.inference.InferenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 推理引擎依赖注入模块
 */
@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {

    /**
     * 提供单例的推理管理器
     */
    @Provides
    @Singleton
    fun provideInferenceManager(): InferenceManager? {
        // 注意：由于我们没有实际的 MNN native 库，这里暂时返回 null
        // 在实际使用时，需要在 Application 中初始化
        // 或者通过 Hilt 的 @EntryPoint 方式获取
        
        // TODO: 当 MNN native 库准备好后，取消注释并正确初始化
        // return InferenceManager(context)
        
        return null
    }
}
