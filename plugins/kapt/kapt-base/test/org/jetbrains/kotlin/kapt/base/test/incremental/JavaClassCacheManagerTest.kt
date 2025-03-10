/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kapt.base.test.incremental

import org.jetbrains.kotlin.kapt.base.incremental.JavaClassCacheManager
import org.jetbrains.kotlin.kapt.base.incremental.SourceFileStructure
import org.jetbrains.kotlin.kapt.base.incremental.SourcesToReprocess
import org.jetbrains.kotlin.kapt.base.test.newCompiledSourcesFolder
import org.jetbrains.kotlin.kapt.base.test.newFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JavaClassCacheManagerTest {
    private lateinit var cache: JavaClassCacheManager
    private lateinit var cacheDir: File
    private lateinit var compiledSources: List<File>

    @BeforeEach
    fun setUp(@TempDir tmp: File) {
        cacheDir = tmp.newFolder("cacheDir")
        compiledSources = listOf(tmp.newCompiledSourcesFolder().also { it.resolve(TEST_PACKAGE_NAME).mkdir() })
        cache = JavaClassCacheManager(cacheDir)
    }


    @Test
    fun testClosingCache() {
        cache.close()

        assertTrue(cacheDir.resolve("java-cache.bin").exists())
        assertTrue(cacheDir.resolve("apt-cache.bin").exists())
    }

    @Test
    fun testMentionedTypes() {
        SourceFileStructure(File("Mentioned.java").toURI()).also {
            it.addDeclaredType("test.Mentioned")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/Mentioned.class").createNewFile()
        }
        SourceFileStructure(File("Src.java").toURI()).also {
            it.addDeclaredType("test.Src")
            it.addMentionedType("test.Mentioned")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/Src.class").createNewFile()
        }
        SourceFileStructure(File("ReferencesSrc.java").toURI()).also {
            it.addDeclaredType("test.ReferencesSrc")
            it.addPrivateType("test.Src")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/ReferencesSrc.class").createNewFile()
        }
        prepareForIncremental()

        val dirtyFiles = cache.invalidateAndGetDirtyFiles(
            listOf(File("Mentioned.java").absoluteFile),
            emptyList(), compiledSources
        ) as SourcesToReprocess.Incremental
        assertEquals(
            listOf(
                File("Mentioned.java").absoluteFile,
                File("Src.java").absoluteFile,
                File("ReferencesSrc.java").absoluteFile
            ), dirtyFiles.toReprocess
        )
    }

    @Test
    fun testPrivateTypes() {
        SourceFileStructure(File("Mentioned.java").toURI()).also {
            it.addDeclaredType("test.Mentioned")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/Mentioned.class").createNewFile()
        }
        SourceFileStructure(File("Src.java").toURI()).also {
            it.addDeclaredType("test.Src")
            it.addPrivateType("test.Mentioned")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/Src.class").createNewFile()
        }
        SourceFileStructure(File("ReferencesSrc.java").toURI()).also {
            it.addDeclaredType("test.ReferencesSrc")
            it.addPrivateType("test.Src")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/ReferencesSrc.class").createNewFile()
        }
        prepareForIncremental()

        val dirtyFiles =
            cache.invalidateAndGetDirtyFiles(
                listOf(File("Mentioned.java").absoluteFile),
                emptyList(),
                compiledSources
            ) as SourcesToReprocess.Incremental
        assertEquals(
            listOf(
                File("Mentioned.java").absoluteFile,
                File("Src.java").absoluteFile
            ), dirtyFiles.toReprocess
        )
    }

    @Test
    fun testMultipleDeclared() {
        SourceFileStructure(File("TwoTypes.java").toURI()).also {
            it.addDeclaredType("test.TwoTypes")
            it.addDeclaredType("test.AnotherType")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/TwoTypes.class").createNewFile()
            compiledSources.single().resolve("test/AnotherType.class").createNewFile()
        }
        SourceFileStructure(File("ReferencesTwoTypes.java").toURI()).also {
            it.addDeclaredType("test.ReferencesTwoTypes")
            it.addPrivateType("test.TwoTypes")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/ReferencesTwoTypes.class").createNewFile()
        }
        SourceFileStructure(File("ReferencesAnotherType.java").toURI()).also {
            it.addDeclaredType("test.ReferencesAnotherType")
            it.addPrivateType("test.AnotherType")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/ReferencesAnotherType.class").createNewFile()
        }
        prepareForIncremental()

        val dirtyFiles =
            cache.invalidateAndGetDirtyFiles(
                listOf(File("TwoTypes.java").absoluteFile),
                emptyList(),
                compiledSources
            ) as SourcesToReprocess.Incremental
        assertEquals(
            listOf(
                File("TwoTypes.java").absoluteFile,
                File("ReferencesTwoTypes.java").absoluteFile,
                File("ReferencesAnotherType.java").absoluteFile
            ), dirtyFiles.toReprocess
        )
    }

    @Test
    fun testWithClasspathChanges() {
        SourceFileStructure(File("Src.java").toURI()).also {
            it.addDeclaredType("test.Src")
            it.addMentionedType("test.Mentioned")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/Src.class").createNewFile()
        }
        prepareForIncremental()

        val dirtyFiles =
            cache.invalidateAndGetDirtyFiles(listOf(), listOf("test/Mentioned"), compiledSources) as SourcesToReprocess.Incremental
        assertEquals(listOf(File("Src.java").absoluteFile), dirtyFiles.toReprocess)
    }

    @Test
    fun testReferencedConstant() {
        SourceFileStructure(File("Constants.java").toURI()).also {
            it.addDeclaredType("test.Constants")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/Constants.class").createNewFile()
        }
        SourceFileStructure(File("MentionsConst.java").toURI()).also {
            it.addDeclaredType("test.MentionsConst")
            it.addMentionedConstant("test.Constants", "CONST")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/MentionsConst.class").createNewFile()
        }
        SourceFileStructure(File("MentionsOtherConst.java").toURI()).also {
            it.addDeclaredType("test.MentionsOtherConst")
            it.addMentionedConstant("test.OtherConstants", "CONST")
            cache.javaCache.addSourceStructure(it)
            compiledSources.single().resolve("test/MentionsOtherConst.class").createNewFile()
        }
        prepareForIncremental()

        val dirtyFiles =
            cache.invalidateAndGetDirtyFiles(
                listOf(File("Constants.java").absoluteFile), emptyList(), compiledSources
            ) as SourcesToReprocess.Incremental
        assertEquals(
            listOf(File("Constants.java").absoluteFile, File("MentionsConst.java").absoluteFile),
            dirtyFiles.toReprocess
        )
    }

    private fun prepareForIncremental() {
        cache.close()
        cache = JavaClassCacheManager(cacheDir)
    }
}

private const val TEST_PACKAGE_NAME = "test"
