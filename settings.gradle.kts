/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // RootEncoder (RTMP/RTSP/SRT publisher) is distributed through JitPack.
    maven {
      url = uri("https://jitpack.io")
      content { includeGroupByRegex("com\\.github\\.pedroSG94.*") }
    }
  }
}

rootProject.name = "GlassesLive"

include(":app")
