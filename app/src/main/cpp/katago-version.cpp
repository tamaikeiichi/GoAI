// KataGo/cpp/main.cpp の Version:: 部分 (main() と他のサブコマンドへの依存を除くため、ここだけ取り出している)。
// 元のコードは KataGo のライセンス (KataGo/LICENSE) に従う
#include "main.h"

#include <sstream>

#define GIT_REVISION "<omitted>"

//------------------------
#include "core/using.h"
//------------------------

string Version::getKataGoVersion() {
  return string("1.18.2");
}

string Version::getKataGoVersionForHelp() {
  return string("KataGo v1.18.2");
}

string Version::getKataGoVersionFullInfo() {
  ostringstream out;
  out << Version::getKataGoVersionForHelp() << endl;
  out << "Git revision: " << Version::getGitRevision() << endl;
  out << "Compile Time: " << __DATE__ << " " << __TIME__ << endl;
#if defined(USE_CUDA_BACKEND)
  out << "Using CUDA backend" << endl;
#if defined(CUDA_TARGET_VERSION)
#define STRINGIFY(x) #x
#define STRINGIFY2(x) STRINGIFY(x)
  out << "Compiled with CUDA version " << STRINGIFY2(CUDA_TARGET_VERSION) << endl;
#endif
#elif defined(USE_TENSORRT_BACKEND)
  out << "Using TensorRT backend" << endl;
#elif defined(USE_METAL_BACKEND)
  out << "Using Metal backend" << endl;
#elif defined(USE_OPENCL_BACKEND)
  out << "Using OpenCL backend" << endl;
#elif defined(USE_ROCM_BACKEND)
  out << "Using ROCm backend" << endl;
#if defined(HIP_VERSION_MAJOR) && defined(HIP_VERSION_MINOR) && defined(HIP_VERSION_PATCH)
  out << "Compiled with HIP version " << HIP_VERSION_MAJOR << "." << HIP_VERSION_MINOR << "." << HIP_VERSION_PATCH << endl;
#endif
#elif defined(USE_EIGEN_BACKEND)
  out << "Using Eigen(CPU) backend" << endl;
#elif defined(USE_ONNX_BACKEND)
  out << "Using ONNX Runtime backend" << endl;
#else
  out << "Using dummy backend" << endl;
#endif

#if defined(USE_AVX2)
  out << "Compiled with AVX2 and FMA instructions" << endl;
#endif
#if defined(COMPILE_MAX_BOARD_LEN)
  out << "Compiled to allow boards of size up to " << COMPILE_MAX_BOARD_LEN << endl;
#endif
#if defined(CACHE_TENSORRT_PLAN) && defined(USE_TENSORRT_BACKEND)
  out << "Compiled with TensorRT plan cache" << endl;
#elif defined(BUILD_DISTRIBUTED)
  out << "Compiled to support contributing to online distributed selfplay" << endl;
#endif

  return out.str();
}

string Version::getGitRevision() {
  return string(GIT_REVISION);
}

string Version::getGitRevisionWithBackend() {
  string s = string(GIT_REVISION);

#if defined(USE_CUDA_BACKEND)
  s += "-cuda";
#elif defined(USE_TENSORRT_BACKEND)
  s += "-trt";
#elif defined(USE_ROCM_BACKEND)
  s += "-rocm";
#elif defined(USE_METAL_BACKEND)
  s += "-metal";
#elif defined(USE_OPENCL_BACKEND)
  s += "-opencl";
#elif defined(USE_EIGEN_BACKEND)
  s += "-eigen";
#elif defined(USE_ONNX_BACKEND)
  s += "-onnx";
#else
  s += "-dummy";
#endif
  return s;
}
