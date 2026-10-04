# Vendored whisper.cpp build metadata.
# Avoid invoking Git: Code On the Go project archives do not contain .git metadata.
set(BUILD_NUMBER 0)
set(BUILD_COMMIT "927cfce3")
set(BUILD_COMPILER "unknown")
set(BUILD_TARGET "unknown")

if(MSVC)
    set(BUILD_COMPILER "${CMAKE_C_COMPILER_ID} ${CMAKE_C_COMPILER_VERSION}")
    set(BUILD_TARGET ${CMAKE_VS_PLATFORM_NAME})
    add_compile_options("$<$<COMPILE_LANGUAGE:C>:/utf-8>")
    add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:/utf-8>")
else()
    execute_process(
        COMMAND sh -c "$@ --version | head -1" _ ${CMAKE_C_COMPILER}
        OUTPUT_VARIABLE OUT
        OUTPUT_STRIP_TRAILING_WHITESPACE
    )
    set(BUILD_COMPILER ${OUT})
    execute_process(
        COMMAND ${CMAKE_C_COMPILER} -dumpmachine
        OUTPUT_VARIABLE OUT
        OUTPUT_STRIP_TRAILING_WHITESPACE
    )
    set(BUILD_TARGET ${OUT})
endif()
