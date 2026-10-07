/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.google.protobuf) apply false
}

allprojects {
    apply(plugin = "java-library")

    version = "v0.26.5-dev-eb53b79"

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.toString()
        options.release.set(17)
    }
}
