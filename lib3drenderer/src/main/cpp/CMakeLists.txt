cmake_minimum_required(VERSION 3.18.1)
project("render_bridge")

add_library(render_bridge SHARED render_bridge.cpp)

target_link_libraries(render_bridge android log)