import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tksoft.goai"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tksoft.goai"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
        ndk {
            // ストア公開に必要な 64bit 版とエミュレータ用
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources {
        // モデルは KataGo (zlib) が gzip のまま読むので、APK 内で二重に圧縮しない。
        // assets の .gz は aapt が勝手に展開してしまうので、.dat を付けて置いている
        noCompress += listOf("dat")
    }
}

// ===== KataGo のネットワーク (重み) =====
// 大きくて GitHub に置けないので、リポジトリには含めずビルド時にダウンロードする (KataGoEngine.kt の KataGoModel)。
// - kata9x9-b18c384nbt-20231025 (約98MB): 9路盤専用に追加学習された公式ネット
//   (KataGo Neural Network License, MIT 相当) https://katagotraining.org/extra_networks/
// - g170e-b10c128-s1141046784-d204142634 (約14MB): 13路・19路用の小さな公式ネット (g170 系は CC0)
//   https://github.com/lightvector/KataGo/releases/tag/v1.3
val katagoModels = mapOf(
    "https://media.katagotraining.org/uploaded/networks/models_extra/kata9x9-b18c384nbt-20231025.bin.gz"
        to "katago_model_9x9.bin.gz.dat",
    "https://github.com/lightvector/KataGo/releases/download/v1.3/g170e-b10c128-s1141046784-d204142634.txt.gz"
        to "katago_model_b10.txt.gz.dat",
)
val assetsDir = layout.projectDirectory.dir("src/main/assets")

val downloadKataGoModel = tasks.register("downloadKataGoModel") {
    description = "Downloads the KataGo networks into assets if they are missing"
    val targets = katagoModels.map { (url, name) -> url to assetsDir.file(name).asFile }
    outputs.files(targets.map { it.second })
    onlyIf { targets.any { !it.second.exists() } }
    doLast {
        for ((url, target) in targets) {
            if (target.exists()) continue
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) error("Failed to move $tmp to $target")
        }
    }
}

tasks.named { it.startsWith("merge") && it.endsWith("Assets") }.configureEach {
    dependsOn(downloadKataGoModel)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
