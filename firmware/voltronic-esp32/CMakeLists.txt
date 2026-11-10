cmake_minimum_required(VERSION 3.16)
# First target: ESP32-S3 only, 2400 baud UART1 + RS232 level shifter.
set(SDKCONFIG_DEFAULTS "sdkconfig.defaults")
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(smart_energy_voltronic_s3)
