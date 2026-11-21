cmake_minimum_required(VERSION 3.22.1)
project(ktvguard)

add_library(ktvguard SHARED signature_guard.cpp)

find_library(log-lib log)
target_link_libraries(ktvguard ${log-lib})
target_compile_features(ktvguard PRIVATE cxx_std_17)
target_compile_options(ktvguard PRIVATE -fvisibility=hidden -fstack-protector-strong)
