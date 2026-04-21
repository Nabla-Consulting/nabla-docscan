package com.nabla.docscan.di

import android.content.Context
import com.nabla.docscan.repository.OneDriveRepository
import com.nabla.docscan.repository.PreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module providing app-level dependencies.
 *
 * Use cases (EnhanceImageUseCase, OcrUseCase, GeneratePdfUseCase) and
 * repositories use @Inject constructors with @ApplicationContext, so Hilt
 * wires them automatically. Only non-@Inject bindings need @Provides here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // All dependencies use @Inject constructor + @ApplicationContext.
    // Hilt handles them automatically — no manual @Provides needed.
    // Add custom bindings here if third-party classes need wrapping.
}
