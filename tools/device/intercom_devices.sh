# shellcheck shell=bash
# Two-device addressing for the P9.0 intercom checks (ADR 0028).
#
# The Office Kiosk is on USB to this machine; the Desk Kiosk is on USB to
# another host reachable over ssh. Both are addressed by serial, and the checks
# never touch net.lockney.tabula's install: the spike app has its own
# applicationId (docs/packets/P9.0-intercom-webrtc-spike.md, constraints).
#
# Override anything here from the environment:
#   TABULA_INTERCOM_A_SERIAL   default G001NW0623720AWP (Office Kiosk, local adb)
#   TABULA_INTERCOM_B_SERIAL   default G001NW0631460BXX (Desk Kiosk, over ssh)
#   TABULA_INTERCOM_B_HOST     default m4mini (the host the Desk Kiosk is attached to)
#   TABULA_INTERCOM_B_PATH     default the Homebrew adb path on that host

intercom_a_serial="${TABULA_INTERCOM_A_SERIAL:-G001NW0623720AWP}"
intercom_b_serial="${TABULA_INTERCOM_B_SERIAL:-G001NW0631460BXX}"
intercom_b_host="${TABULA_INTERCOM_B_HOST:-m4mini}"
intercom_b_path="${TABULA_INTERCOM_B_PATH:-/opt/homebrew/bin:/opt/homebrew/share/android-commandlinetools/platform-tools}"
# shellcheck disable=SC2034  # used by the scripts that source this file
intercom_spike_pkg="${TABULA_INTERCOM_SPIKE_PKG:-net.lockney.tabula.spike.intercom}"
# shellcheck disable=SC2034  # used by the scripts that source this file
intercom_prod_pkg="${TABULA_INTERCOM_PROD_PKG:-net.lockney.tabula}"

# dev_a <adb args...> — the device on this machine.
dev_a() {
    command adb -s "$intercom_a_serial" "$@"
}

# dev_b <adb args...> — the device on the other host, over ssh.
dev_b() {
    local quoted="" arg
    for arg in "$@"; do quoted+=" $(printf '%q' "$arg")"; done
    # One ssh connection per call is slow enough to matter when a check samples
    # every 30 s, so the connection is multiplexed (created on first use, kept
    # for a minute). The arguments are quoted above, on purpose.
    # shellcheck disable=SC2029
    ssh -o ConnectTimeout=10 \
        -o ControlMaster=auto -o ControlPath=/tmp/tabula-intercom-%r@%h:%p -o ControlPersist=60 \
        "$intercom_b_host" \
        "export PATH=$intercom_b_path:\$PATH; adb -s $intercom_b_serial$quoted"
}

# scp_to_b <local path> <remote path>
scp_to_b() {
    scp -q "$1" "$intercom_b_host:$2"
}

# intercom_install <dev_a|dev_b> <apk>
intercom_install() {
    local which="$1" apk="$2" remote="/tmp/spike-intercom.apk"
    if [[ "$which" == dev_a ]]; then
        dev_a install -r "$apk"
    else
        scp_to_b "$apk" "$remote"
        dev_b install -r "$remote"
    fi
}
