cmake_minimum_required(VERSION 3.22.1)
project(quickmt_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(ctranslate2 SHARED IMPORTED)
set_target_properties(ctranslate2 PROPERTIES
    IMPORTED_LOCATION "${JNILIBS_DIR}/libctranslate2.so"
    INTERFACE_INCLUDE_DIRECTORIES "${THIRD_PARTY_DIR}/ctranslate2/include"
)

add_library(sentencepiece STATIC IMPORTED)
set_target_properties(sentencepiece PROPERTIES
    IMPORTED_LOCATION "${THIRD_PARTY_DIR}/sentencepiece/lib/libsentencepiece.a"
    INTERFACE_INCLUDE_DIRECTORIES "${THIRD_PARTY_DIR}/sentencepiece/include"
)

add_library(quickmt_jni SHARED
    quickmt_jni.cpp
)

target_include_directories(quickmt_jni PRIVATE
    "${THIRD_PARTY_DIR}/ctranslate2/include"
    "${THIRD_PARTY_DIR}/sentencepiece/include"
)

target_link_libraries(quickmt_jni
    ctranslate2
    sentencepiece
    android
    log
)
