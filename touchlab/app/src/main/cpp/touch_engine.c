/*
 * touch_engine.c — engine cảm ứng chạy bằng quyền shell (qua Shizuku).
 *
 * Cách dùng:
 *   touchlab_engine <config> [--cmd <path>] [--log <path>] [--name <tên thiết bị>]
 *                   [--dry-run] [--print]
 *
 * Giao thức điều khiển: app ghi từng dòng lệnh vào file --cmd (mặc định
 * <config>.cmd). Engine theo dõi file bằng inotify và thực thi ngay.
 *
 *   RECONFIG                 đọc lại config
 *   TAP <x> <y>              chạm nhanh 40 ms
 *   TRIGGER <idx>            chạy lần lượt target của trigger
 *   TRIGGER_DOWN/UP <idx>    lặp click trong khi giữ nút
 *   SENS_BEGIN <x> <y>       bắt đầu vệt cảm ứng có áp sensitivity
 *   SENS_MOVE <x> <y>        cập nhật vị trí ngón tay (toạ độ tuyệt đối)
 *   SENS_END
 *   DRAG <idx> <dx> <dy>     kéo theo mẫu drag idx
 *   PING / STOP
 *
 * Mã thoát: 0 OK, 2 không mở được /dev/uinput, 3 config lỗi, 4 tham số sai.
 */
#define _GNU_SOURCE

#include "touch_config.h"
#include "uinput_touch.h"

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* Tham chiếu tốc độ cho đường cong accel (px/sample). */
#define ACCEL_REF_PX 60.0f
#define MACRO_CLICK_QUEUE_SIZE 16

static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig) {
    (void) sig;
    g_stop = 1;
}

static void sleep_ms(int ms) {
    if (ms <= 0) {
        return;
    }
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long) (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

/* ---------- logging ---------- */

static FILE *g_log = NULL;

static void logf_msg(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    if (g_log != NULL) {
        va_start(ap, fmt);
        vfprintf(g_log, fmt, ap);
        va_end(ap);
        fputc('\n', g_log);
        fflush(g_log);
    }
}

/* ---------- sensitivity ---------- */

typedef struct {
    int active;
    int in_zone;
    float raw_x, raw_y;
    float out_x, out_y;
    float vel;
} sens_state;

/* Vùng có được áp sensitivity không (zone 0/3 = toàn màn hình). */
static int zone_allows(const tc_config *cfg, float x, float y) {
    if (cfg->zone == 3 && cfg->has_circle) {
        float dx = x - cfg->circle_x;
        float dy = y - cfg->circle_y;
        return dx * dx + dy * dy <= cfg->circle_r * cfg->circle_r;
    }
    if (cfg->zone == 3 && cfg->has_rect) {
        return x >= cfg->rect_x1 && x <= cfg->rect_x2 && y >= cfg->rect_y1 && y <= cfg->rect_y2;
    }
    float mid = (float) cfg->screen_w * 0.5f;
    switch (cfg->zone) {
        case 1:
            return x < mid;
        case 2:
            return x >= mid;
        default:
            return 1;
    }
}

/*
 * Áp sensitivity cho delta chuyển động (không áp cho DOWN/UP — giống engine gốc).
 * accel: gain tăng dần 1.0 → 1.5 theo tốc độ, có làm mượt EMA 0.5.
 */
static void sens_apply(const tc_config *cfg, sens_state *st, float x, float y,
                       float *out_x, float *out_y) {
    float dx = x - st->raw_x;
    float dy = y - st->raw_y;
    st->raw_x = x;
    st->raw_y = y;

    float fx = cfg->sensi_x;
    float fy = cfg->sensi_y;

    if (cfg->accel) {
        /*
         * Tốc độ "cảm nhận" = |dx| / ACCEL_REF_PX cho mỗi sample.
         *
         * Engine gốc dùng (|dx| / bề ngang màn hình) rồi chia cho dt với hằng số
         * (vel - 0.0004) / 0.0026 — thực tế t bão hoà ngay khi dx > ~3 px nên gain
         * gần như luôn bằng 1.5. Ở đây thay bằng ngưỡng tham chiếu 24 px để đường
         * cong accel thật sự biến thiên (1.0 → 1.5) và vẫn giữ nguyên dạng smoothstep.
         */
        float spd = fabsf(dx) / ACCEL_REF_PX;
        st->vel += (spd - st->vel) * 0.5f;         /* EMA 0.5 như bản gốc */
        float t = st->vel;
        if (t < 0.0f) t = 0.0f;
        if (t > 1.0f) t = 1.0f;
        float gain = (t * -2.0f + 3.0f) * t * t * 0.5f + 1.0f;
        fx *= gain;
        fy *= gain;
    }

    st->out_x += dx * fx;
    st->out_y += dy * fy;

    float maxx = (float) (cfg->screen_w - 1);
    float maxy = (float) (cfg->screen_h - 1);
    if (st->out_x < 0.0f) st->out_x = 0.0f;
    if (st->out_x > maxx) st->out_x = maxx;
    if (st->out_y < 0.0f) st->out_y = 0.0f;
    if (st->out_y > maxy) st->out_y = maxy;

    *out_x = st->out_x;
    *out_y = st->out_y;
}

/* ---------- engine state ---------- */

typedef struct {
    tc_config cfg;
    char cfg_path[512];
    int uifd;
    sens_state sens;
} engine;

typedef struct {
    pthread_t thread;
    pthread_mutex_t mutex;
    pthread_cond_t changed;
    engine *engine;
    int started;
    int stopping;
    int hold_active;
    int hold_index;
    int click_queue[MACRO_CLICK_QUEUE_SIZE];
    int click_head;
    int click_count;
    unsigned int generation;
} macro_runner;

static macro_runner g_macro = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .changed = PTHREAD_COND_INITIALIZER,
    .hold_index = -1,
};

static int reload_config(engine *e) {
    char err[256];
    pthread_mutex_lock(&g_macro.mutex);
    if (tc_load(e->cfg_path, &e->cfg, err, sizeof(err)) != 0) {
        pthread_mutex_unlock(&g_macro.mutex);
        logf_msg("config: %s", err);
        return -1;
    }
    pthread_mutex_unlock(&g_macro.mutex);
    logf_msg("config: nạp %s (zone=%d sensi=%.2f/%.2f accel=%d, %d macro, %d drag)",
             e->cfg_path, e->cfg.zone, e->cfg.sensi_x, e->cfg.sensi_y, e->cfg.accel,
             e->cfg.macro_count, e->cfg.drag_count);
    return 0;
}

static int do_tap(engine *e, float x, float y, int hold_ms) {
    if (e->uifd < 0) {
        return -1;
    }
    if (ui_touch_down(e->uifd, x, y) != 0) {
        return -1;
    }
    sleep_ms(hold_ms > 0 ? hold_ms : 40);
    return ui_touch_up(e->uifd, x, y);
}

static int hold_is_active(macro_runner *runner, int idx, unsigned int generation) {
    pthread_mutex_lock(&runner->mutex);
    int active = !runner->stopping && runner->hold_active &&
        runner->hold_index == idx && runner->generation == generation;
    pthread_mutex_unlock(&runner->mutex);
    return active;
}

static void run_macro_clicks(engine *e, const tc_macro *macro, int repeat,
                             int idx, unsigned int generation) {
    for (int i = 0; i < macro->target_count && !g_stop; i++) {
        if (repeat && !hold_is_active(&g_macro, idx, generation)) break;
        const tc_target *target = &macro->targets[i];
        if (ui_touch_down_slot(e->uifd, 1, target->x, target->y) != 0) {
            logf_msg("macro: không thể chạm target %d", i + 1);
            return;
        }
        sleep_ms(macro->press_ms);
        if (ui_touch_up_slot(e->uifd, 1, target->x, target->y) != 0) {
            logf_msg("macro: không thể nhả target %d", i + 1);
            return;
        }
        if (repeat) sleep_ms(macro->hold_delay_ms);
    }
}

static void *macro_worker(void *arg) {
    macro_runner *runner = (macro_runner *) arg;
    engine *e = runner->engine;
    for (;;) {
        pthread_mutex_lock(&runner->mutex);
        while (!runner->stopping && !runner->hold_active && runner->click_count == 0) {
            pthread_cond_wait(&runner->changed, &runner->mutex);
        }
        if (runner->stopping) {
            pthread_mutex_unlock(&runner->mutex);
            break;
        }

        int idx = runner->hold_active ? runner->hold_index :
            runner->click_queue[runner->click_head];
        int is_hold = runner->hold_active;
        unsigned int generation = runner->generation;
        if (!is_hold) {
            runner->click_head = (runner->click_head + 1) % MACRO_CLICK_QUEUE_SIZE;
            runner->click_count--;
        }
        int macro_count = e->cfg.macro_count;
        tc_macro macro = {0};
        int valid = idx >= 0 && idx < macro_count;
        if (valid) macro = e->cfg.macros[idx];
        pthread_mutex_unlock(&runner->mutex);

        if (valid && macro.target_count > 0) {
            if (!is_hold && macro.click_delay_ms > 0) sleep_ms(macro.click_delay_ms);
            run_macro_clicks(e, &macro, is_hold, idx, generation);
        }
    }
    return NULL;
}

static int start_macro_worker(engine *e) {
    g_macro.engine = e;
    if (pthread_create(&g_macro.thread, NULL, macro_worker, &g_macro) != 0) return -1;
    g_macro.started = 1;
    return 0;
}

static void stop_macro_worker(void) {
    if (!g_macro.started) return;
    pthread_mutex_lock(&g_macro.mutex);
    g_macro.stopping = 1;
    g_macro.hold_active = 0;
    pthread_cond_broadcast(&g_macro.changed);
    pthread_mutex_unlock(&g_macro.mutex);
    pthread_join(g_macro.thread, NULL);
    g_macro.started = 0;
}

static int request_macro(engine *e, int idx, int hold) {
    pthread_mutex_lock(&g_macro.mutex);
    if (idx < 0 || idx >= e->cfg.macro_count) {
        int macro_count = e->cfg.macro_count;
        pthread_mutex_unlock(&g_macro.mutex);
        logf_msg("macro: chỉ số %d ngoài phạm vi (0..%d)", idx, macro_count - 1);
        return -1;
    }
    if (e->cfg.macros[idx].mode != (hold ? 1 : 0)) {
        pthread_mutex_unlock(&g_macro.mutex);
        logf_msg("macro: trigger %d không khớp chế độ", idx + 1);
        return -1;
    }
    if (hold) {
        g_macro.hold_index = idx;
        g_macro.hold_active = 1;
        g_macro.generation++;
    } else {
        if (g_macro.click_count == MACRO_CLICK_QUEUE_SIZE) {
            pthread_mutex_unlock(&g_macro.mutex);
            logf_msg("macro: hàng đợi click đã đầy");
            return -1;
        }
        int tail = (g_macro.click_head + g_macro.click_count) % MACRO_CLICK_QUEUE_SIZE;
        g_macro.click_queue[tail] = idx;
        g_macro.click_count++;
    }
    pthread_cond_broadcast(&g_macro.changed);
    pthread_mutex_unlock(&g_macro.mutex);
    return 0;
}

static void release_macro(int idx) {
    pthread_mutex_lock(&g_macro.mutex);
    if (g_macro.hold_active && (idx < 0 || g_macro.hold_index == idx)) {
        g_macro.hold_active = 0;
        g_macro.hold_index = -1;
        g_macro.generation++;
        pthread_cond_broadcast(&g_macro.changed);
    }
    pthread_mutex_unlock(&g_macro.mutex);
}

static int do_drag(engine *e, int idx, float dx, float dy) {
    if (idx < 0 || idx >= e->cfg.drag_count) {
        logf_msg("drag: chỉ số %d ngoài phạm vi", idx);
        return -1;
    }
    const tc_drag *d = &e->cfg.drags[idx];
    int repeats = d->strength > 0 ? d->strength : 1;
    if (repeats > 20) {
        repeats = 20;
    }
    int gap = d->delay > 0 ? d->delay : 8;
    if (gap > 200) {
        gap = 200;
    }
    if (e->uifd < 0) {
        return -1;
    }

    float x = d->x;
    float y = d->y;
    ui_touch_down(e->uifd, x, y);
    sleep_ms(gap);
    /* Chia quãng đường thành nhiều bước nhỏ để game nhận đúng chuỗi move. */
    const int steps = 6;
    for (int r = 0; r < repeats; r++) {
        for (int i = 1; i <= steps; i++) {
            float t = (float) i / (float) steps;
            ui_touch_move(e->uifd, x + dx * t, y + dy * t);
            sleep_ms(gap / 2 > 0 ? gap / 2 : 1);
        }
        for (int i = steps - 1; i >= 0; i--) {
            float t = (float) i / (float) steps;
            ui_touch_move(e->uifd, x + dx * t, y + dy * t);
            sleep_ms(gap / 2 > 0 ? gap / 2 : 1);
        }
    }
    ui_touch_up(e->uifd, x, y);
    return 0;
}

/* ---------- command dispatch ---------- */

static int run_command(engine *e, char *line) {
    while (*line == ' ' || *line == '\t') {
        line++;
    }
    if (*line == '\0' || *line == '#') {
        return 0;
    }

    char verb[32] = {0};
    if (sscanf(line, "%31s", verb) != 1) {
        return 0;
    }

    if (strcmp(verb, "STOP") == 0) {
        g_stop = 1;
        return 0;
    }
    if (strcmp(verb, "PING") == 0) {
        logf_msg("pong");
        return 0;
    }
    if (strcmp(verb, "RECONFIG") == 0) {
        return reload_config(e);
    }
    if (strcmp(verb, "TAP") == 0) {
        float x = 0, y = 0;
        if (sscanf(line, "%*s %f %f", &x, &y) == 2) {
            return do_tap(e, x, y, 40);
        }
        return -1;
    }
    if (strcmp(verb, "MACRO") == 0) {
        int idx = -1;
        if (sscanf(line, "%*s %d", &idx) != 1 || idx < 0 || idx >= e->cfg.macro_count) return -1;
        return request_macro(e, idx, e->cfg.macros[idx].mode == 1);
    }
    if (strcmp(verb, "TRIGGER") == 0) {
        int idx = -1;
        if (sscanf(line, "%*s %d", &idx) != 1 || idx < 0 || idx >= e->cfg.macro_count) return -1;
        return request_macro(e, idx, 0);
    }
    if (strcmp(verb, "TRIGGER_DOWN") == 0 || strcmp(verb, "HOLD") == 0) {
        int idx = -1;
        if (sscanf(line, "%*s %d", &idx) != 1) return -1;
        return request_macro(e, idx, 1);
    }
    if (strcmp(verb, "TRIGGER_UP") == 0) {
        int idx = -1;
        if (sscanf(line, "%*s %d", &idx) != 1) return -1;
        release_macro(idx);
        return 0;
    }
    if (strcmp(verb, "RELEASE") == 0) {
        int idx = -1;
        sscanf(line, "%*s %d", &idx);
        release_macro(idx);
        return 0;
    }
    if (strcmp(verb, "DRAG") == 0) {
        int idx = 0;
        float dx = 0, dy = 0;
        if (sscanf(line, "%*s %d %f %f", &idx, &dx, &dy) == 3) {
            return do_drag(e, idx, dx, dy);
        }
        return -1;
    }
    if (strcmp(verb, "SENS_BEGIN") == 0) {
        float x = 0, y = 0;
        if (sscanf(line, "%*s %f %f", &x, &y) != 2) {
            return -1;
        }
        e->sens.active = 1;
        e->sens.in_zone = zone_allows(&e->cfg, x, y);
        e->sens.raw_x = x;
        e->sens.raw_y = y;
        e->sens.out_x = x;
        e->sens.out_y = y;
        e->sens.vel = 0.0f;
        return ui_touch_down(e->uifd, x, y);
    }
    if (strcmp(verb, "SENS_MOVE") == 0) {
        float x = 0, y = 0;
        if (sscanf(line, "%*s %f %f", &x, &y) != 2 || !e->sens.active) {
            return -1;
        }
        float ox = x, oy = y;
        int in_zone = zone_allows(&e->cfg, x, y);
        if (in_zone && e->sens.in_zone) {
            sens_apply(&e->cfg, &e->sens, x, y, &ox, &oy);
        } else {
            e->sens.raw_x = x;
            e->sens.raw_y = y;
            e->sens.out_x = x;
            e->sens.out_y = y;
            e->sens.vel = 0.0f;
        }
        e->sens.in_zone = in_zone;
        return ui_touch_move(e->uifd, ox, oy);
    }
    if (strcmp(verb, "SENS_END") == 0) {
        float ox = e->sens.out_x;
        float oy = e->sens.out_y;
        e->sens.active = 0;
        e->sens.in_zone = 0;
        return ui_touch_up(e->uifd, ox, oy);
    }

    logf_msg("lệnh không hiểu: '%s'", verb);
    return -1;
}

/* ---------- command file tail ---------- */

typedef struct {
    int ifd;      /* inotify */
    int cfd;      /* command file */
    int wd;
} cmd_watcher;

static int cmd_open(cmd_watcher *w, const char *path) {
    memset(w, 0, sizeof(*w));
    w->cfd = open(path, O_RDONLY | O_CREAT | O_CLOEXEC, 0666);
    if (w->cfd < 0) {
        return -1;
    }
    /* Bỏ nội dung cũ để không phát lại lệnh của lần chạy trước. */
    if (ftruncate(w->cfd, 0) != 0) {
        logf_msg("cmd: không truncate được (%s)", strerror(errno));
    }

    char dir[512];
    snprintf(dir, sizeof(dir), "%s", path);
    char *slash = strrchr(dir, '/');
    if (slash != NULL) {
        *slash = '\0';
    } else {
        snprintf(dir, sizeof(dir), ".");
    }

    w->ifd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (w->ifd < 0) {
        return -1;
    }
    w->wd = inotify_add_watch(w->ifd, dir, IN_MODIFY | IN_CREATE | IN_MOVED_TO);
    return w->wd < 0 ? -1 : 0;
}

static void cmd_close(cmd_watcher *w) {
    if (w->ifd >= 0) {
        if (w->wd >= 0) {
            inotify_rm_watch(w->ifd, w->wd);
        }
        close(w->ifd);
    }
    if (w->cfd >= 0) {
        close(w->cfd);
    }
}

/* Đọc phần mới của file lệnh, tách dòng và thực thi. */
static void cmd_drain(engine *e, cmd_watcher *w) {
    static char pending[4096];
    static size_t pending_len = 0;

    for (;;) {
        char buf[1024];
        ssize_t n = read(w->cfd, buf, sizeof(buf));
        if (n <= 0) {
            break;
        }
        if (pending_len + (size_t) n >= sizeof(pending)) {
            pending_len = 0;   /* quá dài: bỏ phần dở */
        }
        memcpy(pending + pending_len, buf, (size_t) n);
        pending_len += (size_t) n;

        size_t start = 0;
        for (size_t i = 0; i < pending_len; i++) {
            if (pending[i] == '\n') {
                pending[i] = '\0';
                run_command(e, pending + start);
                start = i + 1;
            }
        }
        if (start > 0) {
            memmove(pending, pending + start, pending_len - start);
            pending_len -= start;
        }
    }
}

/* ---------- dry run ---------- */

static int dry_run(const engine *e) {
    printf("== DRY RUN ==\n");
    tc_dump(&e->cfg, stdout);

    sens_state st;
    memset(&st, 0, sizeof(st));
    float x = 300.0f, y = 900.0f;
    st.raw_x = x;
    st.raw_y = y;
    st.out_x = x;
    st.out_y = y;
    printf("\n== mô phỏng sensitivity (accel=%d, hệ số %.2f/%.2f) ==\n", e->cfg.accel,
           e->cfg.sensi_x, e->cfg.sensi_y);
    for (int i = 0; i < 6; i++) {
        float nx = x + 40.0f * (float) (i + 1);
        float ny = y + 12.0f * (float) (i + 1);
        float ox, oy;
        sens_apply(&e->cfg, &st, nx, ny, &ox, &oy);
        printf("  raw(%.1f,%.1f) -> out(%.1f,%.1f)  vel=%.6f\n", nx, ny, ox, oy, st.vel);
    }
    printf("\n== macro ==\n");
    for (int i = 0; i < e->cfg.macro_count; i++) {
        const tc_macro *m = &e->cfg.macros[i];
        printf("  trigger %d: mode=%d, %d target, press=%d ms\n",
               i, m->mode, m->target_count, m->press_ms);
    }
    return 0;
}

/* ---------- main ---------- */

static void usage(void) {
    fprintf(stderr,
            "touchlab_engine <config> [--cmd <path>] [--log <path>] [--name <label>]\n"
            "                [--print] [--dry-run]\n");
}

int main(int argc, char **argv) {
    const char *cfg_path = NULL;
    const char *cmd_path = NULL;
    const char *log_path = NULL;
    const char *dev_name = "TouchLab Virtual Touch";
    int print_only = 0;
    int dry = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--cmd") == 0 && i + 1 < argc) {
            cmd_path = argv[++i];
        } else if (strcmp(argv[i], "--log") == 0 && i + 1 < argc) {
            log_path = argv[++i];
        } else if (strcmp(argv[i], "--name") == 0 && i + 1 < argc) {
            dev_name = argv[++i];
        } else if (strcmp(argv[i], "--print") == 0) {
            print_only = 1;
        } else if (strcmp(argv[i], "--dry-run") == 0) {
            dry = 1;
        } else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            usage();
            return 0;
        } else if (argv[i][0] != '-') {
            cfg_path = argv[i];
        } else {
            usage();
            return 4;
        }
    }

    if (cfg_path == NULL) {
        usage();
        return 4;
    }

    if (log_path != NULL) {
        g_log = fopen(log_path, "ae");
    }

    engine e;
    memset(&e, 0, sizeof(e));
    e.uifd = -1;
    snprintf(e.cfg_path, sizeof(e.cfg_path), "%s", cfg_path);

    if (reload_config(&e) != 0) {
        logf_msg("engine: config không hợp lệ, dừng");
        if (g_log != NULL) fclose(g_log);
        return 3;
    }

    if (print_only) {
        tc_dump(&e.cfg, stdout);
        return 0;
    }

    if (dry) {
        int rc = dry_run(&e);
        if (g_log != NULL) fclose(g_log);
        return rc;
    }

    e.uifd = ui_open_virtual_touch(dev_name, e.cfg.screen_w, e.cfg.screen_h);
    if (e.uifd < 0) {
        logf_msg("uinput: không mở được /dev/uinput (errno %d) — cần chạy bằng shell/root",
                 -e.uifd);
        if (g_log != NULL) fclose(g_log);
        return 2;
    }
    if (start_macro_worker(&e) != 0) {
        logf_msg("macro: không khởi động được worker");
        ui_close_virtual(e.uifd);
        if (g_log != NULL) fclose(g_log);
        return 5;
    }
    logf_msg("engine: sẵn sàng (uinput fd=%d, %dx%d)", e.uifd, e.cfg.screen_w, e.cfg.screen_h);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);

    char default_cmd[512];
    if (cmd_path == NULL) {
        snprintf(default_cmd, sizeof(default_cmd), "%s.cmd", cfg_path);
        cmd_path = default_cmd;
    }

    cmd_watcher w;
    memset(&w, 0, sizeof(w));
    w.ifd = -1;
    w.cfd = -1;
    w.wd = -1;
    if (cmd_open(&w, cmd_path) != 0) {
        logf_msg("cmd: không theo dõi được %s (%s)", cmd_path, strerror(errno));
    }

    /* Xử lý lệnh đã có sẵn trước khi vào vòng lặp. */
    if (w.cfd >= 0) {
        cmd_drain(&e, &w);
    }

    while (!g_stop) {
        if (w.ifd < 0) {
            sleep_ms(200);
            continue;
        }
        struct pollfd pfd;
        pfd.fd = w.ifd;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 200);
        if (pr <= 0) {
            continue;
        }
        char ibuf[1024];
        while (read(w.ifd, ibuf, sizeof(ibuf)) > 0) {
            /* chỉ cần biết có thay đổi, nội dung không quan trọng */
        }
        cmd_drain(&e, &w);
    }

    logf_msg("engine: dừng");
    stop_macro_worker();
    cmd_close(&w);
    ui_close_virtual(e.uifd);
    if (g_log != NULL) {
        fclose(g_log);
    }
    return 0;
}
