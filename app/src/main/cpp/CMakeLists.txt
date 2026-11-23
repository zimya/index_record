cmake_minimum_required(VERSION 3.22.1)
project(usearch_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(usearch_jni SHARED usearch_jni.cpp)

target_include_directories(usearch_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

target_compile_options(usearch_jni PRIVATE
    -O3
    -fvisibility=hidden
    -fno-math-errno
)

find_library(log-lib log)
target_link_libraries(usearch_jni ${log-lib})
