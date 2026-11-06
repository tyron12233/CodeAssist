// ndkrun: runs a user's C/C++ console program in a pseudo-terminal, for the NDK plugin's Run.
//
//   libndkrun.so <program> [args...]
//
// Executes <program> with a pty as its stdin, stdout and stderr, and relays between that pty and its own
// stdin/stdout, which are the pipes the IDE holds. Exits with the program's exit status, or 128+N after
// printing a line naming the signal that killed it.
//
// Why a pty rather than the pipes alone: C's stdio decides how to buffer from isatty(). On a pipe, stdout
// is FULLY buffered, so `printf("Name: "); scanf(...)` shows no prompt until 4 KB of output or the program
// exits -- an interactive program looks hung. On a terminal stdout is line buffered, and stdio flushes it
// before every read from a terminal stdin, which is exactly the behaviour people expect from a console.
//
// The terminal is configured for the IDE's console rather than for a screen: no ECHO (the IDE already
// shows what was typed), no output post-processing (so `\n` stays `\n` instead of becoming `\r\n`), and
// canonical input, so a line the IDE sends arrives as one line.
//
// <program> is passed through as given. On Android 10+ the IDE runs it as `/system/bin/linker64 <file>`:
// an app may not exec() a file in its own storage, but the system linker may load one.
//
// Lifetime: both this process and the program die with their parent (PR_SET_PDEATHSIG), so the IDE's Stop
// -- or the IDE itself dying -- never leaves a user's program running on its own.

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static int write_all(int fd, const char *buf, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, buf, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buf += n;
        len -= (size_t) n;
    }
    return 0;
}

// Copy whatever the program has written to the pty out to our stdout. 0 at end (the program's side of the
// pty is closed: on Linux the master then reads EIO), 1 when there may be more.
static int drain(int master) {
    char buf[8192];
    ssize_t n = read(master, buf, sizeof buf);
    if (n > 0) {
        write_all(STDOUT_FILENO, buf, (size_t) n);
        return 1;
    }
    if (n < 0 && (errno == EINTR || errno == EAGAIN)) return 1;
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <program> [args...]\n", argv[0]);
        return 2;
    }
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    signal(SIGPIPE, SIG_IGN);

    struct winsize size = { .ws_row = 24, .ws_col = 80 };
    int master, slave;
    if (openpty(&master, &slave, NULL, NULL, &size) != 0) {
        fprintf(stderr, "ndkrun: could not open a terminal: %s\n", strerror(errno));
        return 126;
    }
    struct termios tio;
    if (tcgetattr(slave, &tio) == 0) {
        tio.c_lflag &= ~(ECHO | ECHOE | ECHOK | ECHONL | ECHOCTL | ECHOKE);
        tio.c_lflag |= ICANON | ISIG | IEXTEN;
        tio.c_oflag &= ~OPOST;
        tio.c_iflag |= ICRNL;
#ifdef IUTF8
        tio.c_iflag |= IUTF8;
#endif
        tcsetattr(slave, TCSANOW, &tio);
    }

    pid_t child = fork();
    if (child < 0) {
        fprintf(stderr, "ndkrun: fork failed: %s\n", strerror(errno));
        return 126;
    }
    if (child == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        signal(SIGPIPE, SIG_DFL);
        execv(argv[1], &argv[1]);
        fprintf(stderr, "ndkrun: could not start %s: %s\n", argv[1], strerror(errno));
        _exit(127);
    }
    close(slave);

    int input_open = 1;
    int status = 0;
    int reaped = 0;
    for (;;) {
        struct pollfd fds[2] = {
            { .fd = master, .events = POLLIN },
            { .fd = input_open ? STDIN_FILENO : -1, .events = POLLIN },
        };
        int ready = poll(fds, 2, 200);
        if (ready < 0 && errno != EINTR) break;

        if (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            if (!drain(master)) break;
        }
        if (input_open && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            char buf[4096];
            ssize_t n = read(STDIN_FILENO, buf, sizeof buf);
            if (n > 0) {
                write_all(master, buf, (size_t) n);
            } else if (n == 0 || (errno != EINTR && errno != EAGAIN)) {
                // The IDE closed the program's input. In canonical mode the EOF character at the start of a
                // line is what makes the program's read() return 0; the IDE always sends whole lines.
                char eof = tio.c_cc[VEOF] ? (char) tio.c_cc[VEOF] : 4;
                write_all(master, &eof, 1);
                input_open = 0;
            }
        }
        // A program can exit while something it started keeps the terminal open, and then the master never
        // reports the end. Check the program itself, and once it is gone take what output is left.
        if (!reaped && waitpid(child, &status, WNOHANG) == child) {
            reaped = 1;
            int flags = fcntl(master, F_GETFL);
            fcntl(master, F_SETFL, flags | O_NONBLOCK);
            char buf[8192];
            ssize_t n;
            while ((n = read(master, buf, sizeof buf)) > 0) write_all(STDOUT_FILENO, buf, (size_t) n);
            break;
        }
    }
    if (!reaped) waitpid(child, &status, 0);
    close(master);

    if (WIFSIGNALED(status)) {
        int sig = WTERMSIG(status);
        char line[160];
        int len = snprintf(line, sizeof line, "\n[the program was terminated by signal %d: %s]\n", sig, strsignal(sig));
        if (len > 0) write_all(STDOUT_FILENO, line, (size_t) len);
        return 128 + sig;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}
