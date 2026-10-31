import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
        versionCode = 2
        versionName = "0.1.1"

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
// - kata1-b18c384nbt-s9996604416-d4316597426 (約98MB): 13路・19路用の公式ネット (kata1 系, KataGo Neural Network License)
//   https://katagotraining.org/networks/ 。以前の g170e-b10c128 (約14MB) は小さく古いため、
//   大石の死活が絡む局面で勝敗を読み違えることがあったので入れ替えた
// テキスト形式 (.txt.gz) しかないネットは、端末での読み込み (数値の文字列の解析) に数秒かかるので、
// ビルド時にバイナリ形式に変換する (convertTextModel)
// 値: assets のファイル名と、テキスト形式から変換するかどうか
val katagoModels = mapOf(
    "https://media.katagotraining.org/uploaded/networks/models_extra/kata9x9-b18c384nbt-20231025.bin.gz"
        to ("katago_model_9x9.bin.gz.dat" to false),
    "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz"
        to ("katago_model_b18.bin.gz.dat" to false),
)
val assetsDir = layout.projectDirectory.dir("src/main/assets")

val downloadKataGoModel = tasks.register("downloadKataGoModel") {
    description = "Downloads the KataGo networks into assets if they are missing"
    val targets = katagoModels.map { (url, v) -> Triple(url, assetsDir.file(v.first).asFile, v.second) }
    outputs.files(targets.map { it.second })
    onlyIf { targets.any { !it.second.exists() } }
    doLast {
        // (configuration cache のため、スクリプトの関数ではなくタスクの中に置く)
        /**
         * テキスト形式のネット (.txt.gz) をバイナリ形式 (.bin.gz) にする。
         * 見出しや層の大きさの行はそのまま残し、数値が2つ以上並んだ行 (重み) を
         * "@BLK@" + 個数 (uint32) + float32 の並び (どちらもリトルエンディアン) に置き換える。
         * 1つの配列が複数の行にまたがるので、KataGo 標準の "@BIN@" (配列ごと) ではなく行ごとの "@BLK@" にし、
         * 読み込み側 (neuralnet/desc.cpp の readBlockFloats) で行をつなげて配列にする
         */
        fun convertTextModel(src: File, dst: File) {
            GZIPInputStream(src.inputStream().buffered()).bufferedReader().use { reader ->
                GZIPOutputStream(dst.outputStream().buffered()).use { out ->
                    reader.forEachLine { line ->
                        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                        if (tokens.size < 2) {
                            out.write((line + "\n").toByteArray())
                        } else {
                            val buf = ByteBuffer.allocate(9 + tokens.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                            buf.put("@BLK@".toByteArray())
                            buf.putInt(tokens.size)
                            for (t in tokens) buf.putFloat(t.toFloat())
                            out.write(buf.array())
                            out.write('\n'.code)
                        }
                    }
                }
            }
        }

        for ((url, target, fromText) in targets) {
            if (target.exists()) continue
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            logger.lifecycle("Downloading $url")
            URI(url).toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (fromText) {
                val converted = File(target.parentFile, target.name + ".bin.part")
                logger.lifecycle("Converting $url to the binary format")
                convertTextModel(tmp, converted)
                tmp.delete()
                if (!converted.renameTo(target)) error("Failed to move $converted to $target")
            } else if (!tmp.renameTo(target)) error("Failed to move $tmp to $target")
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
