/*
 * SPDX-FileCopyrightText: 2025 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.google.protobuf)
}

sourceSets {
    main {
        java.srcDir("../timeago-parser/src/main/java")
    }
}

tasks.jar {
    exclude("**/*.proto")
    includeEmptyDirs = false
}

dependencies {
    api(libs.newpipe.nanojson)
    implementation(libs.jsoup)
    compileOnly(libs.google.jsr305)
    implementation(libs.google.protobuf)
    implementation(libs.mozilla.rhino.core)
    implementation(libs.mozilla.rhino.engine)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.lib.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}
