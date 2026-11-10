cmake_minimum_required(VERSION 3.22.1)
project(touchlab_engine C)

# Executable chạy bằng quyền shell (Shizuku). Không phụ thuộc STL.
add_executable(touchlab_engine
        touch_engine.c
        touch_config.c
        uinput_touch.c)

# AGP chỉ đóng gói file lib*.so vào APK, nên executable phải mang tên dạng đó.
# Bên trong nó vẫn là ELF PIE chạy được (đổi tên sau khi copy ra /data/local/tmp).
set_target_properties(touchlab_engine PROPERTIES PREFIX "lib" SUFFIX ".so")

target_compile_options(touchlab_engine PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_include_directories(touchlab_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if (ANDROID)
    target_link_libraries(touchlab_engine PRIVATE log)
endif ()
