/*
 * kbdinject - virtual input injector for VHKeyboard (AYN Thor dual-screen).
 *
 * Runs as root (launched via `su`). Creates virtual keyboard + mouse uinput
 * devices and injects events read from stdin. Because they are real kernel input
 * devices, events are routed by Android to the focused window on the top-focused
 * display, so the second-screen keyboard/trackpad drives the main screen.
 *
 * It also folds in the "joyscroll" gamepad->mouse/scroll daemon as a toggleable
 * background thread (right stick = cursor, left stick = scroll, L1/R1 = clicks,
 * other gamepad events passed through a proxy device).
 *
 * Control protocol (line-based, ASCII, one command per line on stdin):
 *   D <code>       key down (evdev KEY_* code, decimal)
 *   U <code>       key up
 *   T <code>       key tap = down + up
 *   M <dx> <dy>    relative mouse move
 *   C <button>     mouse click (0=left, 1=right, 2=middle)
 *   W <dy>         discrete wheel scroll (notches)
 *   H <dy>         high-resolution wheel scroll (1/120 notch units; smooth)
 *   J <0|1>        disable/enable the gamepad->mouse thread
 *   S              sync/flush
 *   P              ping -> prints "OK\n"
 *   Q              quit
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <math.h>
#include <dirent.h>
#include <pthread.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <sys/epoll.h>
#include <sys/timerfd.h>

#ifndef REL_WHEEL_HI_RES
#define REL_WHEEL_HI_RES  0x0b
#endif
#ifndef REL_HWHEEL_HI_RES
#define REL_HWHEEL_HI_RES 0x0c
#endif

/* Inter-event pacing so the input reader never coalesces/drops rapid events. */
#define PRESS_MS   12
#define GAP_MS      3

static void emit(int fd, int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type; ev.code = code; ev.value = val;
    write(fd, &ev, sizeof(ev));
}

static void sync_report(int fd) { emit(fd, EV_SYN, SYN_REPORT, 0); }

/* --- keyboard -------------------------------------------------------------- */
static int create_keyboard(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fprintf(stderr, "kbdinject: open /dev/uinput failed: %s\n", strerror(errno));
        return -1;
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    for (int k = KEY_ESC; k <= KEY_MICMUTE; k++) ioctl(fd, UI_SET_KEYBIT, k);
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    strncpy(setup.name, "VHKeyboard Virtual Keyboard", sizeof(setup.name) - 1);
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x1209; setup.id.product = 0x7654; setup.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "kbdinject: keyboard create failed: %s\n", strerror(errno));
        close(fd); return -1;
    }
    return fd;
}

/* --- mouse ----------------------------------------------------------------- */
static int create_mouse(const char *name, unsigned short product) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL_HI_RES);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL_HI_RES);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    strncpy(setup.name, name, sizeof(setup.name) - 1);
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x1209; setup.id.product = product; setup.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd); return -1;
    }
    return fd;
}

static void mouse_move(int fd, int dx, int dy) {
    if (fd < 0) return;
    if (dx) emit(fd, EV_REL, REL_X, dx);
    if (dy) emit(fd, EV_REL, REL_Y, dy);
    sync_report(fd);
}

static void mouse_click(int fd, int button) {
    if (fd < 0) return;
    int code = button == 1 ? BTN_RIGHT : button == 2 ? BTN_MIDDLE : BTN_LEFT;
    emit(fd, EV_KEY, code, 1); sync_report(fd);
    usleep(PRESS_MS * 1000);
    emit(fd, EV_KEY, code, 0); sync_report(fd);
}

static void mouse_scroll(int fd, int dy) {
    if (fd < 0 || dy == 0) return;
    emit(fd, EV_REL, REL_WHEEL, dy);
    sync_report(fd);
}

/* High-resolution scroll (1/120 notch units). Emits the fine event for smooth
 * scrolling and accumulates full notches to a discrete REL_WHEEL fallback for
 * consumers that ignore hi-res. */
static void mouse_scroll_hires(int fd, int *accum, int hival) {
    if (fd < 0 || hival == 0) return;
    emit(fd, EV_REL, REL_WHEEL_HI_RES, hival);
    *accum += hival;
    while (*accum >= 120) { emit(fd, EV_REL, REL_WHEEL, 1); *accum -= 120; }
    while (*accum <= -120) { emit(fd, EV_REL, REL_WHEEL, -1); *accum += 120; }
    sync_report(fd);
}

/* --- virtual touchscreen (for smooth touch-drag scrolling) ----------------- *
 * Android 13 ignores REL_WHEEL_HI_RES, so smooth scrolling is done by injecting a
 * real finger drag. Coordinates are normalised 0..TOUCH_MAX; Android maps the ABS
 * range onto the associated display's pixels (and handles rotation). MT protocol A.
 */
#define TOUCH_MAX 32767

static int touch_x = TOUCH_MAX / 2;
static int touch_y = TOUCH_MAX / 2;

static int create_touchscreen(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    /* Mark as a direct (absolute) touchscreen, not a touchpad -- otherwise
     * Android treats it as an indirect pointer (moves the cursor) and swipes
     * don't scroll. */
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    struct uinput_abs_setup ax;
    memset(&ax, 0, sizeof(ax));
    ax.absinfo.minimum = 0; ax.absinfo.maximum = TOUCH_MAX;
    ax.code = ABS_MT_POSITION_X; ioctl(fd, UI_ABS_SETUP, &ax);
    ax.code = ABS_MT_POSITION_Y; ioctl(fd, UI_ABS_SETUP, &ax);
    ax.code = ABS_X; ioctl(fd, UI_ABS_SETUP, &ax);
    ax.code = ABS_Y; ioctl(fd, UI_ABS_SETUP, &ax);
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    strncpy(setup.name, "VHKeyboard Virtual Touch", sizeof(setup.name) - 1);
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x1209; setup.id.product = 0x7657; setup.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd); return -1;
    }
    return fd;
}

static void touch_frame(int fd, int down, int x, int y) {
    if (down) {
        emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
        emit(fd, EV_ABS, ABS_MT_POSITION_Y, y);
        emit(fd, EV_SYN, SYN_MT_REPORT, 0);
    } else {
        emit(fd, EV_SYN, SYN_MT_REPORT, 0);  /* empty frame = all up */
    }
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

static void touch_down(int fd, int x, int y) {
    if (fd < 0) return;
    touch_x = x; touch_y = y;
    emit(fd, EV_KEY, BTN_TOUCH, 1);
    touch_frame(fd, 1, x, y);
}
static void touch_move(int fd, int x, int y) {
    if (fd < 0) return;
    touch_x = x; touch_y = y;
    touch_frame(fd, 1, x, y);
}
static void touch_up(int fd) {
    if (fd < 0) return;
    emit(fd, EV_KEY, BTN_TOUCH, 0);
    touch_frame(fd, 0, 0, 0);
}

/* --- keyboard helpers ------------------------------------------------------ */
static void key(int fd, int code, int down) {
    if (code <= 0 || code > KEY_MAX) return;
    emit(fd, EV_KEY, code, down); sync_report(fd);
    usleep(GAP_MS * 1000);
}

static void tap(int fd, int code) {
    if (code <= 0 || code > KEY_MAX) return;
    emit(fd, EV_KEY, code, 1); sync_report(fd);
    usleep(PRESS_MS * 1000);
    emit(fd, EV_KEY, code, 0); sync_report(fd);
    usleep(GAP_MS * 1000);
}

/* --- gamepad -> mouse/scroll (folded joyscroll) ---------------------------- */
#define MOUSE_DEAD_ZONE   4000
#define SCROLL_DEAD_ZONE  6000
#define MAX_VAL           32767
#define TICK_MS           16
#define MOUSE_SENSITIVITY 20.0f
#define SCROLL_SPEED      40.0f   /* hi-res units per tick at full deflection */

static volatile int g_gamepad_enabled = 0;
static volatile int g_quit = 0;
static pthread_t g_gp_thread;

static int find_gamepad(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        if (strncmp(ent->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", ent->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char name[256] = {0};
        ioctl(fd, EVIOCGNAME(sizeof(name)), name);
        if ((strstr(name, "Xbox") || strstr(name, "Odin")) &&
            !strstr(name, "Virtual") && !strstr(name, "Scroll") &&
            !strstr(name, "Station") && !strstr(name, "Joystick")) {
            closedir(dir);
            return fd;
        }
        close(fd);
    }
    closedir(dir);
    return -1;
}

static int create_proxy_gamepad(int real_fd) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    unsigned long evbits[(EV_MAX + 7) / 8 / sizeof(long) + 1] = {0};
    ioctl(real_fd, EVIOCGBIT(0, sizeof(evbits)), evbits);
    for (int ev = 0; ev < EV_MAX; ev++) {
        if (!(evbits[ev / (8 * sizeof(long))] & (1UL << (ev % (8 * sizeof(long)))))) continue;
        ioctl(fd, UI_SET_EVBIT, ev);
        if (ev == EV_KEY) {
            unsigned long bits[KEY_MAX / 8 / sizeof(long) + 1] = {0};
            ioctl(real_fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits);
            for (int k = 0; k < KEY_MAX; k++)
                if (bits[k / (8 * sizeof(long))] & (1UL << (k % (8 * sizeof(long)))))
                    ioctl(fd, UI_SET_KEYBIT, k);
        } else if (ev == EV_ABS) {
            unsigned long bits[ABS_MAX / 8 / sizeof(long) + 1] = {0};
            ioctl(real_fd, EVIOCGBIT(EV_ABS, sizeof(bits)), bits);
            for (int a = 0; a < ABS_MAX; a++)
                if (bits[a / (8 * sizeof(long))] & (1UL << (a % (8 * sizeof(long)))))
                    ioctl(fd, UI_SET_ABSBIT, a);
        }
    }
    int axes[] = {ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y, -1};
    for (int i = 0; axes[i] >= 0; i++) {
        struct uinput_abs_setup as; struct input_absinfo ai;
        memset(&as, 0, sizeof(as));
        as.code = axes[i];
        if (ioctl(real_fd, EVIOCGABS(axes[i]), &ai) == 0) { as.absinfo = ai; ioctl(fd, UI_ABS_SETUP, &as); }
    }
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    strncpy(setup.name, "Xbox Wireless Controller", sizeof(setup.name) - 1);
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x045e; setup.id.product = 0x0b12; setup.id.version = 0x0100;
    ioctl(fd, UI_DEV_SETUP, &setup);
    ioctl(fd, UI_DEV_CREATE);
    return fd;
}

static float axis_to_cursor(int value) {
    int av = abs(value);
    if (av < MOUSE_DEAD_ZONE) return 0.0f;
    float norm = (float)(av - MOUSE_DEAD_ZONE) / (float)(MAX_VAL - MOUSE_DEAD_ZONE);
    return (value < 0 ? -1 : 1) * norm * norm * MOUSE_SENSITIVITY;
}

static float axis_to_scroll(int value) {
    int av = abs(value);
    if (av < SCROLL_DEAD_ZONE) return 0.0f;
    float norm = (float)(av - SCROLL_DEAD_ZONE) / (float)(MAX_VAL - SCROLL_DEAD_ZONE);
    return (value < 0 ? -1 : 1) * norm * norm * norm * SCROLL_SPEED;
}

/* One connect/grab/map session; returns when disabled or the pad disconnects. */
static void gamepad_session(void) {
    int gp = find_gamepad();
    if (gp < 0) { usleep(1500000); return; }
    int mouse = create_mouse("VHKeyboard Gamepad Mouse", 0x7656);
    int proxy = create_proxy_gamepad(gp);
    if (mouse < 0 || proxy < 0) {
        close(gp);
        if (mouse >= 0) { ioctl(mouse, UI_DEV_DESTROY); close(mouse); }
        if (proxy >= 0) { ioctl(proxy, UI_DEV_DESTROY); close(proxy); }
        usleep(1500000); return;
    }
    usleep(400000);
    if (ioctl(gp, EVIOCGRAB, 1) < 0) {
        close(gp);
        ioctl(mouse, UI_DEV_DESTROY); close(mouse);
        ioctl(proxy, UI_DEV_DESTROY); close(proxy);
        usleep(1500000); return;
    }

    int tfd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK);
    struct itimerspec its = {.it_interval = {0, TICK_MS * 1000000}, .it_value = {0, TICK_MS * 1000000}};
    timerfd_settime(tfd, 0, &its, NULL);
    int ep = epoll_create(2);
    struct epoll_event ev;
    ev.events = EPOLLIN; ev.data.fd = gp; epoll_ctl(ep, EPOLL_CTL_ADD, gp, &ev);
    ev.data.fd = tfd; epoll_ctl(ep, EPOLL_CTL_ADD, tfd, &ev);

    int left_x = 0, left_y = 0, right_x = 0, right_y = 0;
    float accum_wheel = 0, accum_hwheel = 0;
    int wheel_accum = 0;
    int disc = 0;
    while (!g_quit && g_gamepad_enabled && !disc) {
        struct epoll_event evs[4];
        int n = epoll_wait(ep, evs, 4, 500);
        for (int i = 0; i < n; i++) {
            if (evs[i].data.fd == gp) {
                struct input_event ie;
                if (read(gp, &ie, sizeof(ie)) != sizeof(ie)) { disc = 1; break; }
                if (ie.type == EV_ABS) {
                    switch (ie.code) {
                        case ABS_X:  left_x = ie.value;  emit(proxy, EV_ABS, ABS_X, 0);  break;
                        case ABS_Y:  left_y = ie.value;  emit(proxy, EV_ABS, ABS_Y, 0);  break;
                        case ABS_Z:  right_x = ie.value; emit(proxy, EV_ABS, ABS_Z, 0);  break;
                        case ABS_RZ: right_y = ie.value; emit(proxy, EV_ABS, ABS_RZ, 0); break;
                        default: write(proxy, &ie, sizeof(ie)); break;
                    }
                } else if (ie.type == EV_KEY) {
                    if (ie.code == BTN_TL) { emit(mouse, EV_KEY, BTN_LEFT, ie.value); sync_report(mouse); }
                    else if (ie.code == BTN_TR) { emit(mouse, EV_KEY, BTN_RIGHT, ie.value); sync_report(mouse); }
                    else write(proxy, &ie, sizeof(ie));
                } else {
                    write(proxy, &ie, sizeof(ie));
                }
            } else if (evs[i].data.fd == tfd) {
                uint64_t exp; read(tfd, &exp, sizeof(exp));
                int dx = (int)axis_to_cursor(right_x);
                int dy = (int)axis_to_cursor(right_y);
                if (dx || dy) mouse_move(mouse, dx, dy);
                accum_wheel += axis_to_scroll(left_y);
                accum_hwheel += axis_to_scroll(left_x);
                int wy = (int)accum_wheel; accum_wheel -= wy;
                int wx = (int)accum_hwheel; accum_hwheel -= wx;
                /* left stick down should scroll down: invert wy for wheel */
                if (wy) mouse_scroll_hires(mouse, &wheel_accum, -wy);
                if (wx) { emit(mouse, EV_REL, REL_HWHEEL, wx > 0 ? 1 : -1); sync_report(mouse); }
            }
        }
    }
    ioctl(gp, EVIOCGRAB, 0);
    close(ep); close(tfd);
    ioctl(proxy, UI_DEV_DESTROY); close(proxy);
    ioctl(mouse, UI_DEV_DESTROY); close(mouse);
    close(gp);
}

static void *gamepad_thread(void *arg) {
    (void)arg;
    while (!g_quit) {
        if (g_gamepad_enabled) gamepad_session();
        else usleep(300000);
    }
    return NULL;
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);

    int fd = create_keyboard();
    if (fd < 0) return 1;
    int mfd = create_mouse("VHKeyboard Virtual Mouse", 0x7655);
    int tsfd = create_touchscreen();
    int wheel_accum = 0;

    pthread_create(&g_gp_thread, NULL, gamepad_thread, NULL);

    usleep(400000);
    fprintf(stderr, "kbdinject: ready\n");

    char line[128];
    while (fgets(line, sizeof(line), stdin) != NULL) {
        char c = line[0];
        int a = 0, b = 0;
        switch (c) {
            case 'D': key(fd, atoi(line + 1), 1); break;
            case 'U': key(fd, atoi(line + 1), 0); break;
            case 'T': tap(fd, atoi(line + 1)); break;
            case 'M': if (sscanf(line + 1, "%d %d", &a, &b) == 2) mouse_move(mfd, a, b); break;
            case 'C': mouse_click(mfd, atoi(line + 1)); break;
            case 'W': mouse_scroll(mfd, atoi(line + 1)); break;
            case 'H': mouse_scroll_hires(mfd, &wheel_accum, atoi(line + 1)); break;
            case 'J': g_gamepad_enabled = atoi(line + 1) ? 1 : 0; break;
            case 'G':
                switch (line[1]) {
                    case 'D': if (sscanf(line + 2, "%d %d", &a, &b) == 2) touch_down(tsfd, a, b); break;
                    case 'M': if (sscanf(line + 2, "%d %d", &a, &b) == 2) touch_move(tsfd, a, b); break;
                    case 'U': touch_up(tsfd); break;
                    case 'T': /* self-test: swipe up from center */
                        touch_down(tsfd, TOUCH_MAX / 2, TOUCH_MAX * 3 / 4);
                        for (int yy = TOUCH_MAX * 3 / 4; yy > TOUCH_MAX / 4; yy -= 600) {
                            touch_move(tsfd, TOUCH_MAX / 2, yy); usleep(8000);
                        }
                        touch_up(tsfd);
                        break;
                    default: break;
                }
                break;
            case 'S': sync_report(fd); break;
            case 'P': printf("OK\n"); break;
            case 'Q': goto done;
            default: break;
        }
    }

done:
    g_quit = 1;
    g_gamepad_enabled = 0;
    pthread_join(g_gp_thread, NULL);
    usleep(50000);
    ioctl(fd, UI_DEV_DESTROY); close(fd);
    if (mfd >= 0) { ioctl(mfd, UI_DEV_DESTROY); close(mfd); }
    if (tsfd >= 0) { ioctl(tsfd, UI_DEV_DESTROY); close(tsfd); }
    return 0;
}
