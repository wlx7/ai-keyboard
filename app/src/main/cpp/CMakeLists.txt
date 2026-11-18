cmake_minimum_required(VERSION 3.22)
project(aikb_rime CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static libraries from fcitx5-android/prebuilt (see tools/fetch_prebuilt.sh).
set(PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/prebuilt")
if(NOT EXISTS "${PREBUILT}/librime/${ANDROID_ABI}/lib/librime.a")
    message(FATAL_ERROR "Missing prebuilt librime for ${ANDROID_ABI}. Run tools/fetch_prebuilt.sh")
endif()

function(prebuilt name lib)
    add_library(${name} STATIC IMPORTED)
    set_target_properties(${name} PROPERTIES IMPORTED_LOCATION "${PREBUILT}/${lib}")
endfunction()

prebuilt(rime          librime/${ANDROID_ABI}/lib/librime.a)
prebuilt(opencc        opencc/${ANDROID_ABI}/lib/libopencc.a)
prebuilt(yaml_cpp      yaml-cpp/${ANDROID_ABI}/lib/libyaml-cpp.a)
prebuilt(leveldb       leveldb/${ANDROID_ABI}/lib/libleveldb.a)
prebuilt(marisa        marisa/${ANDROID_ABI}/lib/libmarisa.a)
prebuilt(glog          glog/${ANDROID_ABI}/lib/libglog.a)
prebuilt(lua           lua/${ANDROID_ABI}/lib/liblua_static.a)
prebuilt(boost_iostreams boost/${ANDROID_ABI}/lib/libboost_iostreams.a)
prebuilt(boost_container boost/${ANDROID_ABI}/lib/libboost_container.a)
prebuilt(boost_random  boost/${ANDROID_ABI}/lib/libboost_random.a)

add_library(aikb_rime SHARED rime_jni.cpp)
target_include_directories(aikb_rime PRIVATE "${PREBUILT}/librime/${ANDROID_ABI}/include")
# librime registers its modules (incl. the lua/octagram/predict plugins) through static
# initialisers, so the whole archive must be kept.
target_link_libraries(aikb_rime
    -Wl,--whole-archive rime -Wl,--no-whole-archive
    opencc yaml_cpp leveldb marisa glog lua
    boost_iostreams boost_container boost_random
    log android)
target_link_options(aikb_rime PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

# ---------------------------------------------------------------------------
# On-device LM scorer (llama.cpp, MIT) — separate library so Chinese input
# works even if the AI model is never loaded.
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod/fp16 exist on essentially all arm64 phones from 2018 on (Cortex-A55/A75+).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif()
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Missing third_party/llama.cpp. Run tools/fetch_llama.sh")
endif()
# Always optimise the math (debug builds default to -O0, which makes ggml 5-20x slower and
# would make every on-device benchmark meaningless). Later flags override the earlier -O0.
add_compile_options(-O3)
add_subdirectory("${LLAMA_DIR}" llama EXCLUDE_FROM_ALL)

add_library(aikb_llm SHARED llm_jni.cpp)
target_link_libraries(aikb_llm llama log android)
target_link_options(aikb_llm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
