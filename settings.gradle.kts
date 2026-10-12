// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier
//
// Stein Bridge. The readable-name tools it builds with (NamedBuild, OpenJar) are the Stein SDK's: the sdk repository
// sits next to this one (../sdk, or -Pstein.sdkDir=...).
plugins {
    // JDK 25 for compiling: Gradle finds an installed one or downloads it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "stein-bridge"

val sdkDir = file(providers.gradleProperty("stein.sdkDir").getOrElse("../sdk"))
require(sdkDir.resolve("settings.gradle.kts").isFile) {
    "the sdk repository was not found at $sdkDir: clone it next to this one, or pass -Pstein.sdkDir=..."
}
includeBuild(sdkDir) { name = "stein-sdk" }
