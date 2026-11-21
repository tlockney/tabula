# shellcheck shell=bash
# Prompts for the hardware checks; sourced by tools/device/check_*.sh.
#
# A prompt needs a person at a terminal. Without one, bash's `read -p` prints
# nothing and blocks on stdin, so a check run by an agent or over ssh without
# a pty hangs silently (possibly with the tablet's radio off). These helpers
# fail fast instead. Scripts never call `read -p` directly; the repo tests
# (tools/tests/test_device_scripts.py) enforce that.

need_tty() { # need_tty <what the prompt asks>
    if [[ ! -t 0 ]]; then
        echo "FAIL: this step needs a person at a terminal: $1" >&2
        echo "      run the script from an interactive shell (or under a pty)" >&2
        exit 3
    fi
}

pause() { # pause <instruction>: wait for Enter
    need_tty "$1"
    read -r -p "$1, then press Enter " _
}

ask() { # ask <question>: fails unless the verifier answers y
    need_tty "$1"
    local answer
    read -r -p "$1 [y/n] " answer
    if [[ "$answer" != y* ]]; then
        echo "FAIL: verifier answered '$answer' to: $1" >&2
        exit 1
    fi
    echo "-- verifier: yes ($1)"
}
