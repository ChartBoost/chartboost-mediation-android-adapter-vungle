/*
 * Copyright 2022-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

rootProject.name = "VungleAdapter"
include(":VungleAdapter")
include(":android-helium-sdk")
include(":ChartboostMediation")
