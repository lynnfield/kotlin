import org.gradle.api.Project
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.project

/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

val Project.intellijVersion
    get() = rootProject.extra["versions.intellijSdk"]

fun Project.intellijCore() = dependencies.project(":dependencies:intellij-core")
fun Project.intellijUtilRt() = "com.jetbrains.intellij.platform:util-rt:$intellijVersion"

fun Project.jpsModel() = "com.jetbrains.intellij.platform:jps-model:$intellijVersion"
fun Project.jpsModelSerialization() = "com.jetbrains.intellij.platform:jps-model-serialization:$intellijVersion"
fun Project.jpsModelImpl() = "com.jetbrains.intellij.platform:jps-model-impl:$intellijVersion"
fun Project.jpsBuildTest() = "com.jetbrains.intellij.idea:jps-build-test:$intellijVersion"
fun Project.jpsBuild() = "com.jetbrains.intellij.platform:jps-build:$intellijVersion"
fun Project.testFramework() = "com.jetbrains.intellij.platform:test-framework:$intellijVersion"
fun Project.devKitJps() = "com.jetbrains.intellij.devkit:devkit-jps:$intellijVersion"
fun Project.intellijPlatformUtil() = "com.jetbrains.intellij.platform:util:$intellijVersion"
fun Project.intellijPlatformUtilBase() = "com.jetbrains.intellij.platform:util-base:$intellijVersion"
fun Project.intellijAnalysis() = "com.jetbrains.intellij.platform:analysis:$intellijVersion"
fun Project.intellijResources() = "com.jetbrains.intellij.platform:resources:$intellijVersion"
fun Project.intellijJDom() = "com.jetbrains.intellij.platform:util-jdom:$intellijVersion"

/**
 * Runtime version of annotations that are already in Kotlin stdlib (historically, Kotlin has the older version of this one).
 *
 * SHOULD NOT BE USED IN COMPILE CLASSPATH!
 *
 * `@NonNull`, `@Nullable` from `idea/annotations.jar` has `TYPE` target which leads to different types treatment in Kotlin compiler.
 * On the other hand, `idea/annotations.jar` contains org/jetbrains/annotations/Async annotations which is required for IDEA debugger.
 *
 * https://youtrack.jetbrains.com/issue/KT-25047/#focus=Comments-27-6974910.0-0
 */
fun Project.intellijRuntimeAnnotations() = "org.jetbrains:annotations:${rootProject.extra["versions.annotations"]}"
