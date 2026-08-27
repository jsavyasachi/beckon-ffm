#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct signal_name {
    const char *name;
    int number;
};

static const struct signal_name known_signals[] = {
    {"HUP", SIGHUP}, {"INT", SIGINT}, {"QUIT", SIGQUIT},
    {"TERM", SIGTERM}, {"USR1", SIGUSR1}, {"USR2", SIGUSR2},
    {"CHLD", SIGCHLD}, {"CONT", SIGCONT}, {"TSTP", SIGTSTP}
#ifdef SIGWINCH
    , {"WINCH", SIGWINCH}
#endif
};

static int signal_number(const char *name) {
    size_t i;
    for (i = 0; i < sizeof(known_signals) / sizeof(known_signals[0]); ++i) {
        if (strcmp(name, known_signals[i].name) == 0) return known_signals[i].number;
    }
    return -1;
}

static int unsafe_signal(int number) {
    return number == SIGUSR2 || number == SIGCHLD;
}

static void usage(const char *program) {
    fprintf(stderr, "usage: %s --signals NAME[,NAME...] [--allow-unsafe-signals] -- command [args...]\n", program);
}

int main(int argc, char **argv) {
    sigset_t set;
    int allow_unsafe = 0;
    int command = -1;
    char *list = NULL;
    char *cursor;
    char *token;
    char *saveptr = NULL;
    char normalized[256] = "";
    size_t normalized_length = 0;
    int i;

    for (i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--signals") == 0 && i + 1 < argc) {
            list = argv[++i];
        } else if (strcmp(argv[i], "--allow-unsafe-signals") == 0) {
            allow_unsafe = 1;
        } else if (strcmp(argv[i], "--") == 0) {
            command = i + 1;
            break;
        } else {
            usage(argv[0]);
            return 64;
        }
    }
    if (list == NULL || command < 0 || command >= argc) {
        usage(argv[0]);
        return 64;
    }

    if (sigemptyset(&set) != 0) {
        perror("sigemptyset");
        return 70;
    }
    cursor = list;
    while ((token = strtok_r(cursor, ",", &saveptr)) != NULL) {
        cursor = NULL;
        int number = signal_number(token);
        if (number < 0) {
            fprintf(stderr, "unsupported signal in allowlist: %s\n", token);
            return 64;
        }
        if (unsafe_signal(number) && !allow_unsafe) {
            fprintf(stderr, "refusing unsafe signal %s; use --allow-unsafe-signals only with explicit review\n", token);
            return 64;
        }
        if (sigaddset(&set, number) != 0) {
            perror("sigaddset");
            return 70;
        }
        if (normalized_length != 0) normalized[normalized_length++] = ',';
        normalized_length += (size_t)snprintf(normalized + normalized_length,
                                               sizeof(normalized) - normalized_length,
                                               "%s", token);
        if (normalized_length >= sizeof(normalized)) {
            fprintf(stderr, "signal allowlist is too long\n");
            return 64;
        }
    }
    if (normalized_length == 0) {
        fprintf(stderr, "signal allowlist must not be empty\n");
        return 64;
    }
    if (allow_unsafe) {
        fprintf(stderr, "warning: unsafe external signal override enabled; review SIGUSR2/SIGCHLD behavior\n");
    }
    if (pthread_sigmask(SIG_BLOCK, &set, NULL) != 0) {
        perror("pthread_sigmask");
        return 70;
    }

    if (setenv("BECKON_EXTERNAL_SIGNALS", normalized, 1) != 0 ||
        (allow_unsafe && setenv("BECKON_EXTERNAL_ALLOW_UNSAFE", "1", 1) != 0)) {
        perror("setenv");
        return 70;
    }
    execvp(argv[command], &argv[command]);
    fprintf(stderr, "exec %s failed: %s\n", argv[command], strerror(errno));
    return 127;
}
