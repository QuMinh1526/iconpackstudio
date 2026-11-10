/*
 * touch_config.h — cấu trúc config của engine, bám sát format zayon_touch.cfg
 * nhưng dùng hệ số sensitivity GỐC (không nén) cho dễ đọc.
 *
 * Format file:
 *   <sensiX> <sensiY> <zone> <accel> 0 <rotation>
 *   <screenW> <screenH> <macroCount>
 *   [macro] <x1> <y1> <r> <x2> <y2> <mode> <p0> <p1>      (macroCount dòng)
 *   <dragCount>
 *   [drag]  <x> <y> <r> <strength> <kind> <delay>          (dragCount dòng)
 *   [Z <x1> <y1> <x2> <y2>]                                (tuỳ chọn — custom zone)
 *   [F <x> <y> <r>]                                        (tuỳ chọn)
 *   [P <token>]                                            (tuỳ chọn)
 */
#ifndef TOUCHLAB_TOUCH_CONFIG_H
#define TOUCHLAB_TOUCH_CONFIG_H

#include <stdio.h>
#include <stddef.h>

#define TC_MAX_MACROS 8
#define TC_MAX_DRAGS  2
#define TC_MAX_PERMIT 256

/* Một nút macro trên overlay. */
typedef struct {
    float x1, y1;      /* tâm nút (px màn hình) */
    float r;           /* bán kính nút = size / 2 */
    float x2, y2;      /* tâm vùng trigger phụ */
    int mode;          /* 0..3 — chọn bảng thời gian giữ */
    int p0, p1;        /* p0 = thời gian giữ (ms), p1 = trễ bổ sung trước khi nhả */
} tc_macro;

/* Một điểm drag / stable-aim. */
typedef struct {
    float x, y, r;
    int strength;      /* 1..100 */
    int kind;          /* -1 = drag, 1 = stable/fire */
    int delay;         /* ms */
} tc_drag;

typedef struct {
    float sensi_x, sensi_y;   /* hệ số gốc, 1.0 .. 3.45 */
    int zone;                 /* 0 = toàn màn hình, 1 = trái, 2 = phải, 3 = custom/all */
    int accel;                /* bật accel phi tuyến */
    int has_rect;             /* dòng 'Z' (custom zone) có mặt */
    float rect_x1, rect_y1, rect_x2, rect_y2;
    int rotation;             /* 0..3 */
    int screen_w, screen_h;
    tc_macro macros[TC_MAX_MACROS];
    int macro_count;
    tc_drag drags[TC_MAX_DRAGS];
    int drag_count;
    int has_cross;
    float cross_x, cross_y, cross_r;
    char permit[TC_MAX_PERMIT];
} tc_config;

/* Đọc + parse config. Trả 0 nếu OK, -1 nếu lỗi (err chứa mô tả). */
int tc_load(const char *path, tc_config *out, char *err, size_t errlen);

/* In config ra stdout (dùng cho --print / --dry-run). */
void tc_dump(const tc_config *cfg, FILE *out);

#endif /* TOUCHLAB_TOUCH_CONFIG_H */
