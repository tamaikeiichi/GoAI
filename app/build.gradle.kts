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
// 約 98MB あり GitHub に置けないので、リポジトリには含めずビルド時にダウンロードする。
// kata9x9-b18c384nbt-20231025: 9路盤専用に追加学習された公式ネット (KataGo Neural Network License, MIT 相当)
// https://katagotraining.org/extra_networks/
val katagoModelUrl =
    "https://media.katagotraining.org/uploaded/networks/models_extra/kata9x9-b18c384nbt-20231025.bin.gz"
val katagoModelFile = layout.projectDirectory.file("src/main/assets/katago_model_9x9.bin.gz.dat")

val downloadKataGoModel = tasks.register("downloadKataGoModel") {
    description = "Downloads the KataGo 9x9 network into assets if it is missing"
    val url = katagoModelUrl
    val target = katagoModelFile.asFile
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(target)) error("Failed to move $tmp to $target")
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
