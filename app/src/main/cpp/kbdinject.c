/*
 * kbdinject - virtual keyboard injector for VHKeyboard (AYN Thor dual-screen).
 *
 * Runs as root (launched via `su`). Creates a uinput virtual keyboard device and
 * emits key events read from stdin. Because it is a real kernel input device, its
 * key events are routed by Android to the currently focused window regardless of
 * which display the keyboard UI is drawn on -- so a non-focusable keyboard overlay
 * on the second screen types into the focused editor on the main screen.
 *
 * Control protocol (line-based, ASCII, one command per line on stdin):
 *   D <code>   key down   (evdev KEY_* code, decimal)
 *   U <code>   key up
 *   T <code>   tap = down + up
 *   S          sync/flush (a SYN_REPORT is also emitted after every D/U/T)
 *   P          ping -> prints "OK\n" to stdout (readiness handshake)
 *   Q          quit
 *
 * Modifiers are ordinary evdev keycodes (KEY_LEFTSHIFT=42, KEY_LEFTCTRL=29,
 * KEY_LEFTALT=56, KEY_LEFTMETA=125); the caller brackets a key with modifier
 * down/up lines as needed.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>

static int create_keyboard(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        fprintf(stderr, "kbdinject: open /dev/uinput failed: %s\n", strerror(errno));
        return -1;
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    /* Enable the full standard key range (covers a PC keyboard: letters, digits,
     * F1-F24, modifiers, arrows, nav, keypad, media). */
    for (int k = KEY_ESC; k <= KEY_MICMUTE; k++) {
        ioctl(fd, UI_SET_KEYBIT, k);
    }

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    strncpy(setup.name, "VHKeyboard Virtual Keyboard", sizeof(setup.name) - 1);
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor  = 0x1209;
    setup.id.product = 0x7654;
    setup.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
        fprintf(stderr, "kbdinject: UI_DEV_SETUP failed: %s\n", strerror(errno));
        close(fd);
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        fprintf(stderr, "kbdinject: UI_DEV_CREATE failed: %s\n", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void emit(int fd, int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = val;
    write(fd, &ev, sizeof(ev));
}

static void sync_report(int fd) {
    emit(fd, EV_SYN, SYN_REPORT, 0);
}

/* Inter-event pacing so the input reader never coalesces/drops rapid events. */
#define PRESS_MS   12   /* down -> up dwell time for a tap  */
#define GAP_MS      3   /* settle time after a state change */

static void key(int fd, int code, int down) {
    if (code <= 0 || code > KEY_MAX) return;
    emit(fd, EV_KEY, code, down);
    sync_report(fd);
    usleep(GAP_MS * 1000);
}

static void tap(int fd, int code) {
    if (code <= 0 || code > KEY_MAX) return;
    emit(fd, EV_KEY, code, 1);
    sync_report(fd);
    usleep(PRESS_MS * 1000);
    emit(fd, EV_KEY, code, 0);
    sync_report(fd);
    usleep(GAP_MS * 1000);
}

/* --- virtual mouse (for the trackpad in the deck's utility area) ----------- */
static int create_mouse(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    strncpy(setup.name, "VHKeyboard Virtual Mouse", sizeof(setup.name) - 1);
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor  = 0x1209;
    setup.id.product = 0x7655;
    setup.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
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
    int code = BTN_LEFT;
    if (button == 1) code = BTN_RIGHT;
    else if (button == 2) code = BTN_MIDDLE;
    emit(fd, EV_KEY, code, 1);
    sync_report(fd);
    usleep(PRESS_MS * 1000);
    emit(fd, EV_KEY, code, 0);
    sync_report(fd);
}

static void mouse_scroll(int fd, int dy) {
    if (fd < 0 || dy == 0) return;
    emit(fd, EV_REL, REL_WHEEL, dy);
    sync_report(fd);
}

int main(void) {
    /* Unbuffered stdout so handshake/logging is immediate. */
    setvbuf(stdout, NULL, _IONBF, 0);

    int fd = create_keyboard();
    if (fd < 0) return 1;
    int mfd = create_mouse();   /* trackpad; non-fatal if it fails */

    /* Give Android's input reader a moment to register the new devices before the
     * first events arrive, otherwise the earliest events can be dropped. */
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
            case 'S': sync_report(fd); break;
            case 'P': printf("OK\n"); break;
            case 'Q': goto done;
            default: break;
        }
    }

done:
    /* Flush before tearing the devices down so the last events aren't lost. */
    usleep(50000);
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    if (mfd >= 0) { ioctl(mfd, UI_DEV_DESTROY); close(mfd); }
    return 0;
}
