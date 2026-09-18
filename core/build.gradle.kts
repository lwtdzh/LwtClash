import android.databinding.tool.ext.capitalizeUS
import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin

plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
    id("golang-android")
}

val golangSource = file("src/main/golang/native")
val mihomoSource = file("src/foss/golang/clash")
val mihomoNetworkResetPatch = file("patches/mihomo-network-reset.patch")

val applyMihomoNetworkResetPatch = tasks.register("applyMihomoNetworkResetPatch") {
    inputs.file(mihomoNetworkResetPatch)

    doLast {
        val check = project.exec {
            workingDir(mihomoSource)
            commandLine("git", "apply", "--check", mihomoNetworkResetPatch)
            isIgnoreExitValue = true
        }

        if (check.exitValue == 0) {
            project.exec {
                workingDir(mihomoSource)
                commandLine("git", "apply", mihomoNetworkResetPatch)
            }
        } else {
            val alreadyApplied = project.exec {
                workingDir(mihomoSource)
                commandLine("git", "apply", "--reverse", "--check", mihomoNetworkResetPatch)
                isIgnoreExitValue = true
            }

            check(alreadyApplied.exitValue == 0) {
                "Mihomo network reset patch does not apply cleanly"
            }
        }
    }
}

golang {
    sourceSets {
        create("alpha") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        create("meta") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
}

afterEvaluate {
    tasks.withType(GolangBuildTask::class.java).forEach {
        it.inputs.dir(golangSource)
        it.dependsOn(applyMihomoNetworkResetPatch)

        if (it.name.contains("Debug")) {
            val arguments = it.commandLine.toMutableList()
            val tagsIndex = arguments.indexOf("-tags")

            if (tagsIndex >= 0 && tagsIndex + 1 < arguments.size) {
                arguments[tagsIndex + 1] = arguments[tagsIndex + 1]
                    .toString()
                    .split(",")
                    .filterNot { tag -> tag == "debug" }
                    .joinToString(",")
                it.setCommandLine(arguments)
            }
        }
    }
}

val abis = listOf("arm64-v8a" to "Arm64V8a", "armeabi-v7a" to "ArmeabiV7a", "x86" to "X86", "x86_64" to "X8664")

androidComponents.onVariants { variant ->
    val cmakeName = if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"

    abis.forEach { (abi, goAbi) ->
        tasks.configureEach {
            if (name.startsWith("buildCMake$cmakeName[$abi]")) {
                dependsOn("externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
                println("Set up dependency: $name -> externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
            }
        }
    }
}
