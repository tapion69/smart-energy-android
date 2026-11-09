cmake_minimum_required(VERSION 3.16)
# Copy official TailTiny v3.1.0 components into ./components as described in README.
set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/components")
# No installation-specific credentials must enter a public firmware.
set(SDKCONFIG_DEFAULTS "sdkconfig.defaults")
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(smart_energy_vpn_gateway)
