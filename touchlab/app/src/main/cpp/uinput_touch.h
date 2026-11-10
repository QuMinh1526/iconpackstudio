/*
 * uinput_touch.h — thiết bị chạm ảo qua /dev/uinput.
 * Cần quyền ghi /dev/uinput ⇒ tiến trình phải chạy bằng shell (Shizuku) hoặc root.
 */
#ifndef TOUCHLAB_UINPUT_TOUCH_H
#define TOUCHLAB_UINPUT_TOUCH_H

#include <stddef.h>

/* Trả về fd (>= 0) hoặc -errno nếu lỗi. */
int ui_open_virtual_touch(const char *name, int screen_w, int screen_h);

void ui_close_virtual(int fd);

/* x, y tính bằng pixel màn hình; nội bộ scale về 0..32767. */
int ui_touch_down(int fd, float x, float y);
int ui_touch_move(int fd, float x, float y);
int ui_touch_up(int fd, float x, float y);

/* Macro targets use their own slot so they do not cancel the user's Sensi touch. */
int ui_touch_down_slot(int fd, int slot, float x, float y);
int ui_touch_move_slot(int fd, int slot, float x, float y);
int ui_touch_up_slot(int fd, int slot, float x, float y);

#endif /* TOUCHLAB_UINPUT_TOUCH_H */
