# TouchLab — Macro + Sensitivity không cần root (Shizuku + uinput)

Bản viết lại (Kotlin + Jetpack Compose) của các tính năng chính trong `com.zayon.optimize`:
macro nhiều nút, sensitivity theo vùng kèm accel phi tuyến, overlay cấu hình, và engine native
bơm touch qua `/dev/uinput` bằng quyền shell của **Shizuku**.

Phân tích app gốc: xem `/tmp/zayon_logic_summary.md` (luồng end-to-end, format cfg, công thức
sensi/accel) và `/tmp/macro_sensei_modules.md` (chi tiết module + mức obfuscate của `.so`).

## Yêu cầu

| Thành phần | Ghi chú |
|---|---|
| Android 8.0+ (API 26) | `TYPE_APPLICATION_OVERLAY`, FGS |
| **arm64-v8a** | engine chỉ build cho arm64 (điện thoại hiện đại) |
| Shizuku (đã chạy dịch vụ) | cấp quyền API cho TouchLab |
| Quyền "Hiển thị trên ứng dụng khác" | để vẽ overlay |
| Kernel có `/dev/uinput` | hầu hết máy; một số ROM chặn |

## Build

```bash
# cần: JDK 17–21, Android SDK 35, NDK 27, CMake 3.22.1
echo "sdk.dir=/đường/dẫn/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

Kết quả: `app/build/outputs/apk/debug/app-debug.apk`.

Engine là **executable PIE** (không phải `.so` thường) nhưng phải mang tên `libtouchlab_engine.so`
để AGP đóng gói vào `lib/arm64-v8a/`; lúc chạy app copy nó ra `/data/local/tmp/touchlab/touchlab_engine`
và `chmod 700`.

## Cấu trúc

```
app/src/main/cpp/                 engine native (C, không phụ thuộc STL)
  touch_engine.c                  main loop: uinput + inotify đọc file lệnh + sensi/macro
  touch_config.c/.h               parser config 7 phần (giống thứ tự zayon_touch.cfg)
  uinput_touch.c/.h               mở /dev/uinput, UI_DEV_SETUP (fallback kernel 4.4), down/move/up

app/src/main/java/dev/touchlab/
  shizuku/ShizukuHelper.kt        ping binder, xin quyền, chạy shell qua IShizukuService
  engine/EngineController.kt      deploy engine, start/stop, gửi lệnh qua shell nền
  config/TouchConfigManager.kt    lưu JSON (SharedPreferences) + sinh cfg + đẩy xuống engine
  model/Models.kt                 MacroModel, SensiModel, DragModel, ZonePreset, AppState
  overlay/OverlayService.kt       FGS + WindowManager (mỗi nút là 1 cửa sổ nhỏ)
  overlay/OverlayContent.kt       Compose: nút macro, nút menu, lớp sensi, panel, editor vùng
  overlay/OverlayComposeView.kt   Compose host cho window overlay (LifecycleOwner…)
  lab/TouchSampler.kt             đo sampling rate + thời gian giữ
  ui/*                            màn hình chính: Home, Macro, Sensi, Game Lab
```

## Cách hoạt động

1. App xin quyền Shizuku → có binder tới tiến trình shell (uid 2000).
2. `EngineController` copy engine ra `/data/local/tmp/touchlab/`, ghi config, chạy:
   ```
   touchlab_engine <cfg> --cmd <cmd> --log <log>
   ```
3. Engine mở `/dev/uinput`, tạo thiết bị touch ảo, rồi theo dõi file `cmd` bằng **inotify**.
   App ghi mỗi lệnh là một dòng vào file đó qua một shell nền sống lâu (không spawn lại shell
   cho từng lệnh — quan trọng với sensitivity 60–120 Hz).
4. Lệnh hỗ trợ: `TRIGGER idx` (chạy các target theo thứ tự), `TRIGGER_DOWN/UP idx` (Hold),
   `DRAG idx dx dy`, `SENS_BEGIN/MOVE/END`, `RECONFIG`, `PING`, `STOP`.
5. Overlay: mỗi nút macro là một cửa sổ nhỏ (không phải 1 cửa sổ full-screen) nên không chặn
   touch của game ở vùng khác. Panel/editor là cửa sổ full-screen tạm thời.

## Định dạng config

Giữ đúng thứ tự dòng của Zayon:

```
<sensiX> <sensiY> <zone> <accel> 0 <rotation>     ①
<screenW> <screenH> <macroCount> 2                ② (format v2)
<triggerX> <triggerY> <r> <mode> <holdDelay> <pressMs> <clickDelay> <targetCount> ③
<targetX> <targetY> <r>                          ③a × targetCount
<dragCount>                                       ④
<x> <y> <r> <strength> <kind> <delay>             ⑤ × dragCount
S <centerX> <centerY> <radius>                    ⑥ tuỳ chọn: vùng tròn Sensi
```

`zone`: 0 = toàn màn hình, 1 = nửa trái, 2 = nửa phải, 3 = vùng tròn tự chọn.
`mode` macro: 0 = Click (trễ trước lượt chạy), 1 = Hold (lặp khi giữ, có khoảng nghỉ sau mỗi click).
Mỗi trigger có tối đa 10 target; target được bấm theo thứ tự lưu trong config.

## Khác biệt so với bản gốc (có chủ ý)

- **Hệ số sensi là hệ số gốc 1.0–3.45**, không ghi qua lớp nén `(x-1)*0.6+1.05` của Zayon,
  nên số trong UI đọc được trực tiếp.
- **Accel**: giữ nguyên dạng smoothstep `gain = (t*-2+3)*t*t*0.5 + 1` (1.0 → 1.5) nhưng
  chuẩn hoá tốc độ theo `ACCEL_REF_PX = 60 px/sample` thay vì `|dx|/screenWidth`. Hằng số gốc
  (`-0.0004`, `0.0026`) làm `t` bão hoà ngay khi dx > ~3 px, tức gain gần như luôn 1.5 — không
  còn là đường cong. Đổi ngưỡng giữ được cảm giác tăng dần. Xem comment trong `touch_engine.c`.
- Config lưu bằng **1 JSON** trong SharedPreferences (bản gốc dùng 15 key rời).
- Không có phần permit/license, không giải mã `zayon_sound.cfg`, không anti-tamper.

## Giới hạn đã biết

- Chỉ arm64-v8a.
- Bật sensitivity sẽ **chặn touch trực tiếp của game**: overlay bắt touch rồi engine bơm lại
  touch đã scale (đây là cách các app dạng này hoạt động).
- Engine không chạy được nếu kernel/ROM chặn `/dev/uinput` hoặc SELinux hạn chế shell; log
  trong app sẽ ghi rõ lỗi.
- Chưa chạy trên thiết bị thật. Sensi cần quyền overlay và `/dev/uinput`; một số ROM có thể
  giới hạn các quyền này dù Shizuku đã được cấp.
