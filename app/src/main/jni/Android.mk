LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
include $(CLEAR_VARS)
LOCAL_MODULE := indexrecord_pili
LOCAL_SRC_FILES := pili_bridge.cpp pili_http.cpp
LOCAL_CPP_FEATURES := exceptions
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror -fvisibility=hidden
LOCAL_LDLIBS := -ldl
LOCAL_LDFLAGS := -Wl,--version-script=$(LOCAL_PATH)/pili_exports.map
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/pili_exports.map
include $(BUILD_SHARED_LIBRARY)
endif
