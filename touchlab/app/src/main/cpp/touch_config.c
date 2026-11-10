/*
 * touch_config.c — parser tuần tự cho file config.
 * Vì thứ tự dòng quyết định ý nghĩa, parser đọc đúng thứ tự và báo lỗi rõ ràng.
 */
#include "touch_config.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static int clampi(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static char *trim(char *s) {
    while (*s == ' ' || *s == '\t') {
        s++;
    }
    size_t n = strlen(s);
    while (n > 0) {
        char c = s[n - 1];
        if (c != '\n' && c != '\r' && c != ' ' && c != '\t') {
            break;
        }
        s[--n] = '\0';
    }
    return s;
}

/* Đọc dòng kế tiếp, bỏ qua dòng trống và comment '#'. 1 = có dữ liệu. */
static int next_line(FILE *f, char *buf, size_t cap) {
    while (fgets(buf, (int) cap, f) != NULL) {
        char *p = trim(buf);
        if (*p == '\0' || *p == '#') {
            continue;
        }
        if (p != buf) {
            memmove(buf, p, strlen(p) + 1);
        }
        return 1;
    }
    return 0;
}

int tc_load(const char *path, tc_config *out, char *err, size_t errlen) {
    if (path == NULL || out == NULL) {
        return -1;
    }
    memset(out, 0, sizeof(*out));
    out->sensi_x = 1.0f;
    out->sensi_y = 1.0f;
    out->zone = 1;
    out->screen_w = 1080;
    out->screen_h = 2400;

    FILE *f = fopen(path, "re");
    if (f == NULL) {
        snprintf(err, errlen, "không mở được config %s (%s)", path, strerror(errno));
        return -1;
    }

    char line[1024];
    int dummy = 0;

    /* (1) sensiX sensiY zone accel 0 rotation */
    if (!next_line(f, line, sizeof(line))) {
        snprintf(err, errlen, "config rỗng");
        goto fail;
    }
    if (sscanf(line, "%f %f %d %d %d %d", &out->sensi_x, &out->sensi_y, &out->zone,
               &out->accel, &dummy, &out->rotation) != 6) {
        snprintf(err, errlen, "dòng 1 (sensi) sai format: '%s'", line);
        goto fail;
    }
    out->zone = clampi(out->zone, 0, 3);
    out->accel = out->accel != 0;
    out->rotation = clampi(out->rotation, 0, 3);
    out->sensi_x = clampf(out->sensi_x, 1.0f, 3.45f);
    out->sensi_y = clampf(out->sensi_y, 1.0f, 3.45f);

    /* (2) screenW screenH macroCount */
    if (!next_line(f, line, sizeof(line)) ||
        sscanf(line, "%d %d %d", &out->screen_w, &out->screen_h, &out->macro_count) != 3) {
        snprintf(err, errlen, "dòng 2 (kích thước màn hình) sai format");
        goto fail;
    }
    out->screen_w = clampi(out->screen_w, 1, 20000);
    out->screen_h = clampi(out->screen_h, 1, 20000);
    out->macro_count = clampi(out->macro_count, 0, TC_MAX_MACROS);

    /* (3) macro lines */
    for (int i = 0; i < out->macro_count; i++) {
        if (!next_line(f, line, sizeof(line))) {
            snprintf(err, errlen, "thiếu dòng macro thứ %d", i + 1);
            goto fail;
        }
        tc_macro *m = &out->macros[i];
        if (sscanf(line, "%f %f %f %f %f %d %d %d", &m->x1, &m->y1, &m->r, &m->x2, &m->y2,
                   &m->mode, &m->p0, &m->p1) != 8) {
            snprintf(err, errlen, "dòng macro %d sai format: '%s'", i + 1, line);
            goto fail;
        }
        m->mode = clampi(m->mode, 0, 3);
        if (m->p0 > 0) {
            m->p0 = clampi(m->p0, 10, 5000);   /* sàn 10 ms như engine gốc */
        }
        m->p1 = clampi(m->p1, 0, 5000);
        if (m->r < 4.0f) {
            m->r = 4.0f;
        }
    }

    /* (4) dragCount */
    if (!next_line(f, line, sizeof(line)) ||
        sscanf(line, "%d", &out->drag_count) != 1) {
        snprintf(err, errlen, "thiếu dòng dragCount");
        goto fail;
    }
    out->drag_count = clampi(out->drag_count, 0, TC_MAX_DRAGS);

    /* (5) drag lines */
    for (int i = 0; i < out->drag_count; i++) {
        if (!next_line(f, line, sizeof(line))) {
            snprintf(err, errlen, "thiếu dòng drag thứ %d", i + 1);
            goto fail;
        }
        tc_drag *d = &out->drags[i];
        if (sscanf(line, "%f %f %f %d %d %d", &d->x, &d->y, &d->r, &d->strength, &d->kind,
                   &d->delay) != 6) {
            snprintf(err, errlen, "dòng drag %d sai format: '%s'", i + 1, line);
            goto fail;
        }
        d->strength = clampi(d->strength, 1, 100);
        d->delay = clampi(d->delay, 0, 5000);
        if (d->r < 4.0f) {
            d->r = 4.0f;
        }
    }

    /* (6)(7) tuỳ chọn: F ... và P ... */
    while (next_line(f, line, sizeof(line))) {
        if (line[0] == 'Z' && (line[1] == ' ' || line[1] == '\0')) {
            if (sscanf(line, "Z %f %f %f %f", &out->rect_x1, &out->rect_y1,
                       &out->rect_x2, &out->rect_y2) == 4) {
                if (out->rect_x2 < out->rect_x1) {
                    float t = out->rect_x1;
                    out->rect_x1 = out->rect_x2;
                    out->rect_x2 = t;
                }
                if (out->rect_y2 < out->rect_y1) {
                    float t = out->rect_y1;
                    out->rect_y1 = out->rect_y2;
                    out->rect_y2 = t;
                }
                out->has_rect = 1;
            }
        } else if (line[0] == 'F' && (line[1] == ' ' || line[1] == '\0')) {
            if (sscanf(line, "F %f %f %f", &out->cross_x, &out->cross_y, &out->cross_r) == 3) {
                out->has_cross = 1;
            }
        } else if (line[0] == 'P' && (line[1] == ' ' || line[1] == '\0')) {
            snprintf(out->permit, sizeof(out->permit), "%s", line + 1);
        }
    }

    fclose(f);
    return 0;

fail:
    fclose(f);
    return -1;
}

void tc_dump(const tc_config *cfg, FILE *out) {
    fprintf(out, "sensi      : x=%.2f y=%.2f\n", cfg->sensi_x, cfg->sensi_y);
    fprintf(out, "zone       : %d  accel=%d  rotation=%d\n", cfg->zone, cfg->accel, cfg->rotation);
    fprintf(out, "screen     : %dx%d\n", cfg->screen_w, cfg->screen_h);
    for (int i = 0; i < cfg->macro_count; i++) {
        const tc_macro *m = &cfg->macros[i];
        fprintf(out, "macro[%d]   : (%.1f,%.1f) r=%.1f  trigger=(%.1f,%.1f) mode=%d p0=%d p1=%d\n",
                i, m->x1, m->y1, m->r, m->x2, m->y2, m->mode, m->p0, m->p1);
    }
    for (int i = 0; i < cfg->drag_count; i++) {
        const tc_drag *d = &cfg->drags[i];
        fprintf(out, "drag[%d]    : (%.1f,%.1f) r=%.1f strength=%d kind=%d delay=%d\n",
                i, d->x, d->y, d->r, d->strength, d->kind, d->delay);
    }
    if (cfg->has_rect) {
        fprintf(out, "zone rect  : (%.1f,%.1f)-(%.1f,%.1f)\n", cfg->rect_x1, cfg->rect_y1,
                cfg->rect_x2, cfg->rect_y2);
    }
    if (cfg->has_cross) {
        fprintf(out, "crosshair  : (%.1f,%.1f) r=%.1f\n", cfg->cross_x, cfg->cross_y, cfg->cross_r);
    }
    if (cfg->permit[0] != '\0') {
        fprintf(out, "permit     : %s\n", cfg->permit);
    }
}
