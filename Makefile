export THEOS = C:/Users/jakef/theos
export THEOS_PLATFORM = iphone
TARGET = iphone:clang:latest:14.0

include $(THEOS)/makefiles/common.mk

NAME = BaseusSecurityTweak
BASEPACKAGE = com.antigravity.baseussecurity.tweak

BaseusSecurityTweak_FILES = tweak.xm
BaseusSecurityTweak_FRAMEWORKS = UIKit AVFoundation
BaseusSecurityTweak_CFLAGS = -fobjc-arc

include $(THEOS_MAKE_PATH)/tweak.mk
