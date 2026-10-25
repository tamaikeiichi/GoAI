// KataGo (KataGo/cpp) 用の JNI ラッパー。
// KataGo は GTP を標準入出力でやり取りするので、cin をブロッキングキューに、cout を Kotlin へのコールバックに差し替え、
// MainCmds::gtp() ("katago gtp -model ... -config ..." 相当) を呼ぶ。"quit" が来るまで nativeStart は戻らない
#include <jni.h>
#include <string>
#include <vector>
#include <iostream>
#include <streambuf>
#include <android/log.h>
#include <unistd.h>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <memory>
#include <chrono>

#include "main.h"

#define LOG_TAG "GoAI_KataGo"

static JavaVM* g_vm = nullptr;
static jobject g_obj = nullptr;
static jmethodID g_mid = nullptr;
// ヒープ確保: exit() による global dtor でも破棄されない
static std::mutex& g_mutex = *new std::mutex();

// cout -> Kotlinコールバックへリダイレクトするバッファ。
// KataGo は解析結果 (kata-analyze) を探索スレッドから書き出すので、行の組み立てごとロックする
class KataOutBuf : public std::streambuf {
protected:
    int_type overflow(int_type c) override {
        if (c != EOF) {
            std::lock_guard<std::recursive_mutex> lock(lineMutex);
            putChar((char)c);
        }
        return c;
    }
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        std::lock_guard<std::recursive_mutex> lock(lineMutex);
        for (std::streamsize i = 0; i < n; i++) putChar(s[i]);
        return n;
    }
private:
    std::string line;
    std::recursive_mutex lineMutex;
    void putChar(char c) {
        if (c == '\n') sendToKotlin();
        else           line += c;
    }
    void sendToKotlin() {
        // GTP の応答の終わりは空行なので、空行もそのまま送る
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_obj || !g_vm) { line.clear(); return; }
        JNIEnv* env;
        bool attached = false;
        if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) { line.clear(); return; }
            attached = true;
        }
        jstring jline = env->NewStringUTF(line.c_str());
        env->CallVoidMethod(g_obj, g_mid, jline);
        env->DeleteLocalRef(jline);
        if (attached) g_vm->DetachCurrentThread();
        line.clear();
    }
};

// cerr -> logcat (KataGo の起動メッセージやエラーを見るため)
class KataLogBuf : public std::streambuf {
protected:
    int_type overflow(int_type c) override {
        if (c != EOF) {
            std::lock_guard<std::mutex> lock(mtx_);
            if (c == '\n') { __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", line_.c_str()); line_.clear(); }
            else line_ += (char)c;
        }
        return c;
    }
private:
    std::string line_;
    std::mutex mtx_;
};

// cin をブロッキングキューで置き換えるバッファ
class KataCinBuf : public std::streambuf {
public:
    void push_line(const std::string& s) {
        { std::lock_guard<std::mutex> lock(mtx_); queue_.push(s + "\n"); }
        cv_.notify_one();
    }
protected:
    int_type underflow() override {
        if (gptr() < egptr()) return traits_type::to_int_type(*gptr());
        std::unique_lock<std::mutex> lock(mtx_);
        cv_.wait(lock, [this]{ return !queue_.empty(); });
        line_ = std::move(queue_.front());
        queue_.pop();
        lock.unlock();
        if (line_.empty()) return traits_type::eof();
        setg(&line_[0], &line_[0], &line_[0] + line_.size());
        return traits_type::to_int_type(*gptr());
    }
private:
    std::string line_;
    std::mutex mtx_;
    std::condition_variable cv_;
    std::queue<std::string> queue_;
};

// shared_ptr で管理: nativeSendCommand がローカルコピーを保持している間は
// nativeStart が終了してもオブジェクトが破壊されない
static std::shared_ptr<KataCinBuf> g_cin_buf;
static std::mutex& g_cin_mutex = *new std::mutex();

// nativeStart の多重起動を防ぐ。nativeStop は "quit" を投入するだけで終了を待たないため、
// 直後の nativeStart は前のエンジンが MainCmds::gtp を抜けるまで待つ
static bool g_running = false;
static std::mutex& g_running_mutex = *new std::mutex();
static std::condition_variable& g_running_cv = *new std::condition_variable();

static std::string jstringToString(JNIEnv* env, jstring s) {
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string r(p);
    env->ReleaseStringUTFChars(s, p);
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tksoft_goai_KataGoEngine_nativeStart(JNIEnv* env, jobject thiz, jstring modelPath, jstring configPath) {
    {
        std::unique_lock<std::mutex> lock(g_running_mutex);
        if (!g_running_cv.wait_for(lock, std::chrono::seconds(10), []{ return !g_running; })) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "nativeStart: previous engine did not exit in time");
            return -1;
        }
        g_running = true;
    }

    env->GetJavaVM(&g_vm);
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_obj = env->NewGlobalRef(thiz);
        jclass clazz = env->GetObjectClass(thiz);
        g_mid = env->GetMethodID(clazz, "onOutput", "(Ljava/lang/String;)V");
    }

    auto cin_buf = std::make_shared<KataCinBuf>();
    {
        std::lock_guard<std::mutex> lock(g_cin_mutex);
        g_cin_buf = cin_buf;
    }
    std::streambuf* old_cin = std::cin.rdbuf(cin_buf.get());

    KataOutBuf out_buf;
    std::streambuf* old_cout = std::cout.rdbuf(&out_buf);
    KataLogBuf log_buf;
    std::streambuf* old_cerr = std::cerr.rdbuf(&log_buf);

    std::vector<std::string> args = {
        // main.cpp の handleSubcommand と同じく、先頭 (プログラム名) を除いた引数を渡す
        "gtp",
        "-model", jstringToString(env, modelPath),
        "-config", jstringToString(env, configPath),
    };
    int result = 1;
    try {
        result = MainCmds::gtp(args);  // "quit" が来るまでブロック
    } catch (std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "KataGo exception: %s", e.what());
        std::cout << "? KataGo error: " << e.what() << std::endl << std::endl;
    }

    std::cin.rdbuf(old_cin);
    std::cout.rdbuf(old_cout);
    std::cerr.rdbuf(old_cerr);

    {
        std::lock_guard<std::mutex> lock(g_cin_mutex);
        g_cin_buf.reset();
    }
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        env->DeleteGlobalRef(g_obj);
        g_obj = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(g_running_mutex);
        g_running = false;
    }
    g_running_cv.notify_all();
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tksoft_goai_KataGoEngine_nativeSendCommand(JNIEnv* env, jobject thiz, jstring command) {
    std::string cmd = jstringToString(env, command);
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "sendCommand: %s", cmd.c_str());
    std::shared_ptr<KataCinBuf> buf;
    {
        std::lock_guard<std::mutex> lock(g_cin_mutex);
        buf = g_cin_buf;
    }
    if (buf) buf->push_line(cmd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tksoft_goai_KataGoEngine_nativeStop(JNIEnv* env, jobject thiz) {
    std::shared_ptr<KataCinBuf> buf;
    {
        std::lock_guard<std::mutex> lock(g_cin_mutex);
        buf = g_cin_buf;
    }
    if (buf) buf->push_line("quit");
}

extern "C" JNIEXPORT void JNICALL
Java_com_tksoft_goai_KataGoEngine_nativeSetWorkDir(JNIEnv* env, jobject thiz, jstring path) {
    std::string p = jstringToString(env, path);
    chdir(p.c_str());
}
