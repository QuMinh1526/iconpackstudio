/*
 * uinput_touch.c — đăng ký 1 thiết bị multi-touch ảo và bơm sự kiện.
 *
 * Thiết bị đăng ký: EV_KEY/BTN_TOUCH + EV_ABS (ABS_X, ABS_Y, ABS_MT_*).
 * Toạ độ 0..32767 được map tuyến tính từ pixel màn hình.
 */
#define _GNU_SOURCE

#include "uinput_touch.h"

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define ABS_MAX_VALUE 32767

static int g_screen_w = 1080;
static int g_screen_h = 2400;
static int g_tracking_id = 1;

/* Khai báo dải toạ độ cho một trục (kernel >= 4.5). */
static int setup_abs(int fd, int axis, int min, int max) {
    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = (__u16) axis;
    abs.absinfo.minimum = min;
    abs.absinfo.maximum = max;
    return ioctl(fd, UI_ABS_SETUP, &abs);
}

/* Fallback cho kernel cũ (Android 8, kernel 4.4) không có UI_ABS_SETUP. */
static int create_legacy(int fd, const char *name) {
    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "%s", name);
    dev.id.bustype = BUS_VIRTUAL;
    dev.id.vendor = 0x1d6b;
    dev.id.product = 0x0104;
    dev.id.version = 1;
    dev.ff_effects_max = 0;

    const int axes[] = {ABS_X, ABS_Y, ABS_MT_POSITION_X, ABS_MT_POSITION_Y};
    for (size_t i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) {
        dev.absmin[axes[i]] = 0;
        dev.absmax[axes[i]] = ABS_MAX_VALUE;
    }
    dev.absmin[ABS_MT_SLOT] = 0;
    dev.absmax[ABS_MT_SLOT] = 9;
    dev.absmin[ABS_MT_TRACKING_ID] = 0;
    dev.absmax[ABS_MT_TRACKING_ID] = 65535;
    dev.absmin[ABS_MT_TOUCH_MAJOR] = 0;
    dev.absmax[ABS_MT_TOUCH_MAJOR] = 255;

    if (write(fd, &dev, sizeof(dev)) != (ssize_t) sizeof(dev)) {
        return -1;
    }
    return ioctl(fd, UI_DEV_CREATE);
}

static int emit(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (__u16) type;
    ev.code = (__u16) code;
    ev.value = value;
    ssize_t n = write(fd, &ev, sizeof(ev));
    return n == (ssize_t) sizeof(ev) ? 0 : -1;
}

static int to_abs_x(float x) {
    float v = x * (float) ABS_MAX_VALUE / (float) (g_screen_w > 1 ? g_screen_w - 1 : 1);
    if (v < 0.0f) v = 0.0f;
    if (v > (float) ABS_MAX_VALUE) v = (float) ABS_MAX_VALUE;
    return (int) (v + 0.5f);
}

static int to_abs_y(float y) {
    float v = y * (float) ABS_MAX_VALUE / (float) (g_screen_h > 1 ? g_screen_h - 1 : 1);
    if (v < 0.0f) v = 0.0f;
    if (v > (float) ABS_MAX_VALUE) v = (float) ABS_MAX_VALUE;
    return (int) (v + 0.5f);
}

int ui_open_virtual_touch(const char *name, int screen_w, int screen_h) {
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        return -errno;
    }

    /* Bật các loại sự kiện cần dùng. */
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_X) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_Y) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y) < 0 ||
        ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID) < 0) {
        int e = -errno;
        close(fd);
        return e;
    }

const char *label = (name != NULL && name[0] != '\0') ? name : "TouchLab Virtual Touch";

    int rc = -1;
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x1d6b;
    setup.id.product = 0x0104;
    setup.id.version = 1;
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "%s", label);
    setup.ff_effects_max = 0;

    if (ioctl(fd, UI_DEV_SETUP, &setup) == 0) {
        int ok = 1;
        const int axes[] = {ABS_X, ABS_Y, ABS_MT_POSITION_X, ABS_MT_POSITION_Y};
        for (size_t i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) {
            if (setup_abs(fd, axes[i], 0, ABS_MAX_VALUE) != 0) {
                ok = 0;
                break;
            }
        }
        if (ok && setup_abs(fd, ABS_MT_SLOT, 0, 9) == 0 &&
            setup_abs(fd, ABS_MT_TRACKING_ID, 0, 65535) == 0 &&
            setup_abs(fd, ABS_MT_TOUCH_MAJOR, 0, 255) == 0 &&
            ioctl(fd, UI_DEV_CREATE) == 0) {
            rc = 0;
        }
    }
    if (rc != 0) {
        /* Kernel cũ: dùng API legacy trước khi bỏ cuộc. */
        rc = create_legacy(fd, label);
    }
    if (rc != 0) {
        int e = -errno;
        close(fd);
        return e != 0 ? e : -EIO;
    }

    g_screen_w = screen_w > 1 ? screen_w : 1080;
    g_screen_h = screen_h > 1 ? screen_h : 2400;

    /* uinput cần một nhịp để userspace (InputReader) nhận thiết bị mới. */
    struct timespec ts = {0, 120 * 1000 * 1000L};
    nanosleep(&ts, NULL);
    return fd;
}

void ui_close_virtual(int fd) {
    if (fd < 0) {
        return;
    }
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

static int emit_position(int fd, float x, float y) {
    int ax = to_abs_x(x);
    int ay = to_abs_y(y);
    if (emit(fd, EV_ABS, ABS_X, ax) < 0) return -1;
    if (emit(fd, EV_ABS, ABS_Y, ay) < 0) return -1;
    if (emit(fd, EV_ABS, ABS_MT_POSITION_X, ax) < 0) return -1;
    if (emit(fd, EV_ABS, ABS_MT_POSITION_Y, ay) < 0) return -1;
    return 0;
}

int ui_touch_down(int fd, float x, float y) {
    if (fd < 0) return -1;
    if (emit(fd, EV_ABS, ABS_MT_SLOT, 0) < 0) return -1;
    if (emit(fd, EV_ABS, ABS_MT_TRACKING_ID, g_tracking_id++) < 0) return -1;
    if (g_tracking_id > 65000) g_tracking_id = 1;
    if (emit_position(fd, x, y) < 0) return -1;
    if (emit(fd, EV_ABS, ABS_MT_TOUCH_MAJOR, 6) < 0) return -1;
    if (emit(fd, EV_KEY, BTN_TOUCH, 1) < 0) return -1;
    return emit(fd, EV_SYN, SYN_REPORT, 0);
}

int ui_touch_move(int fd, float x, float y) {
    if (fd < 0) return -1;
    if (emit_position(fd, x, y) < 0) return -1;
    return emit(fd, EV_SYN, SYN_REPORT, 0);
}

int ui_touch_up(int fd, float x, float y) {
    if (fd < 0) return -1;
    if (emit_position(fd, x, y) < 0) return -1;
    if (emit(fd, EV_KEY, BTN_TOUCH, 0) < 0) return -1;
    if (emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1) < 0) return -1;
    return emit(fd, EV_SYN, SYN_REPORT, 0);
}
