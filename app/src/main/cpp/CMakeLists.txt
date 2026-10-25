cmake_minimum_required(VERSION 3.22.1)
project("goai")

# ===== KataGo (Eigen CPU バックエンド) =====
# KataGo/cpp は lightvector/KataGo v1.18.2 の cpp/ から GTP に必要な部分だけを取り出したもの。
# 元の CMakeLists.txt の add_executable(katago ...) のうち、GTP 以外のサブコマンド (selfplay, match, tests など) と
# main.cpp は除いている。main() の代わりに native-lib-katago.cpp から MainCmds::gtp() を呼ぶ
set(KATAGO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/KataGo/cpp")
set(KATAGO_SOURCES
    core/global.cpp core/base64.cpp core/bsearch.cpp core/commandloop.cpp core/config_parser.cpp
    core/datetime.cpp core/elo.cpp core/fancymath.cpp core/fileutils.cpp core/hash.cpp core/logger.cpp
    core/mainargs.cpp core/makedir.cpp core/md5.cpp core/multithread.cpp core/parallel.cpp core/rand.cpp
    core/rand_helpers.cpp core/sha2.cpp core/test.cpp core/threadsafecounter.cpp core/threadsafequeue.cpp
    core/threadtest.cpp core/timer.cpp
    game/board.cpp game/rules.cpp game/boardhistory.cpp game/graphhash.cpp
    dataio/sgf.cpp dataio/numpywrite.cpp dataio/poswriter.cpp dataio/trainingwrite.cpp dataio/loadmodel.cpp
    dataio/homedata.cpp dataio/files.cpp
    neuralnet/nninputs.cpp neuralnet/sgfmetadata.cpp neuralnet/modelversion.cpp neuralnet/nneval.cpp
    neuralnet/desc.cpp neuralnet/debugprint.cpp neuralnet/eigenbackend.cpp
    book/book.cpp book/bookcssjs.cpp
    search/timecontrols.cpp search/searchparams.cpp search/mutexpool.cpp search/search.cpp
    search/searchnode.cpp search/searchresults.cpp search/searchhelpers.cpp search/searchexplorehelpers.cpp
    search/searchmirror.cpp search/searchmultithreadhelpers.cpp search/searchnnhelpers.cpp
    search/searchtimehelpers.cpp search/searchupdatehelpers.cpp search/asyncbot.cpp
    search/distributiontable.cpp search/localpattern.cpp search/searchnodetable.cpp
    search/subtreevaluebiastable.cpp search/evalcache.cpp search/patternbonustable.cpp
    search/analysisdata.cpp search/reportedsearchvalues.cpp
    program/gtpconfig.cpp program/setup.cpp program/playutils.cpp program/playsettings.cpp program/play.cpp
    program/selfplaymanager.cpp
    tests/testcommon.cpp
    command/commandline.cpp command/gtp.cpp
)
list(TRANSFORM KATAGO_SOURCES PREPEND "${KATAGO_DIR}/")

add_library(katago SHARED ${KATAGO_SOURCES} katago-version.cpp native-lib-katago.cpp)

target_include_directories(katago PRIVATE ${KATAGO_DIR} ${KATAGO_DIR}/external)
target_include_directories(katago SYSTEM PRIVATE
    ${KATAGO_DIR}/external/tclap-1.2.5/include
    ${KATAGO_DIR}/external/filesystem-1.5.8/include
    # Eigen 3.4.0 (ヘッダのみ)。EIGEN_MPL2_ONLY で MPL2 以外のライセンスのコードを使わないようにしている
    ${CMAKE_CURRENT_SOURCE_DIR}/eigen
)

target_compile_definitions(katago PRIVATE
    USE_EIGEN_BACKEND
    EIGEN_MPL2_ONLY
    NO_GIT_REVISION
    NO_LIBZIP
    NDEBUG
)

if(${ANDROID_ABI} STREQUAL "x86_64")
    target_compile_options(katago PRIVATE -msse4.2 -mpopcnt)
endif()

target_compile_options(katago PRIVATE
    -std=c++17
    -O3
    # KataGo の sha2.cpp などは BYTE_ORDER を使うが <endian.h> を include していない (Linux では他のヘッダ経由で入る)
    "SHELL:-include endian.h"
    -fexceptions
    -frtti
    -fvisibility=hidden
    -Wno-unused-parameter
    -Wno-deprecated-declarations
)

target_link_options(katago PRIVATE "-Wl,-z,max-page-size=16384")
# zlib (.bin.gz のモデル読み込み) は NDK 付属の libz を使う
target_link_libraries(katago android log z)
