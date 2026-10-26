#!/bin/bash
set -Eeuo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=akis-service.sh
source "$SCRIPT_DIR/akis-service.sh"

if [[ ${1:-} == --help && $# == 1 ]]; then
    printf 'Kullanim: %s\nBackend, PostgreSQL, Nginx ve yerel backend health durumu gosterilir.\n' "$0"
    exit 0
fi
if (( $# != 0 )); then
    akis_error 'Kullanim: status.sh'
    exit 2
fi
akis_require_commands systemctl timeout curl python3
akis_snapshot

pg_state=$(timeout --foreground 5s systemctl is-active postgresql.service 2>/dev/null || true)
nginx_state=$(timeout --foreground 5s systemctl is-active nginx.service 2>/dev/null || true)
printf 'Backend: %s / %s; PID=%s\n' "$AKIS_ACTIVE_STATE" "$AKIS_SUB_STATE" "$AKIS_MAIN_PID"
printf 'PostgreSQL: %s\n' "${pg_state:-bilinmiyor}"
printf 'Nginx: %s\n' "${nginx_state:-bilinmiyor}"

if [[ $AKIS_ACTIVE_STATE == inactive && $AKIS_MAIN_PID == 0 ]]; then
    printf 'Backend health: sorgulanmadi; backend durmus.\n'
    exit 3
fi
if [[ $AKIS_ACTIVE_STATE != active ]]; then
    printf 'Backend health: sorgulanmadi; backend active degil.\n'
    exit 1
fi
if ! akis_health_up; then
    printf 'Backend health: UP dogrulanamadi.\n'
    exit 1
fi
printf 'Backend health: UP\n'
if [[ $pg_state != active || $nginx_state != active ]]; then
    exit 1
fi
exit 0
