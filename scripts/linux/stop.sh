#!/bin/bash
set -Eeuo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=akis-service.sh
source "$SCRIPT_DIR/akis-service.sh"

if [[ ${1:-} == --help && $# == 1 ]]; then
    printf 'Kullanim: %s [bekleme_saniye]\nYalniz backend akis.service durdurulur. Varsayilan: 90 saniye.\n' "$0"
    exit 0
fi
if (( $# > 1 )); then
    akis_error 'Kullanim: stop.sh [bekleme_saniye]'
    exit 2
fi
WAIT_SECONDS=${1:-90}
akis_validate_wait "$WAIT_SECONDS"
akis_require_commands id systemctl timeout
akis_require_root
akis_snapshot

if [[ $AKIS_ACTIVE_STATE == inactive && $AKIS_MAIN_PID == 0 ]]; then
    printf 'BASARILI: %s zaten durmus.\n' "$AKIS_SERVICE"
    exit 0
fi
printf '%s icin kontrollu durdurma istegi veriliyor.\n' "$AKIS_SERVICE"
if ! timeout --foreground 10s systemctl stop --no-block "$AKIS_SERVICE"; then
    akis_error 'Durdurma istegi basarisiz veya komut zaman asimina ugradi; servis durumu incelenmeli.'
    akis_diagnostics_hint
    exit 1
fi

deadline=$((SECONDS + WAIT_SECONDS))
while (( SECONDS < deadline )); do
    akis_snapshot
    if [[ $AKIS_ACTIVE_STATE == inactive && $AKIS_MAIN_PID == 0 ]]; then
        printf 'BASARILI: %s inactive; PID=0. PostgreSQL ve Nginx icin durdurma istegi verilmedi.\n' "$AKIS_SERVICE"
        exit 0
    fi
    if [[ $AKIS_ACTIVE_STATE == failed ]]; then
        akis_error "$AKIS_SERVICE failed durumunda; durdurma kabul edilmedi."
        akis_diagnostics_hint
        exit 1
    fi
    if (( SECONDS < deadline )); then sleep 1; fi
done

akis_error "$WAIT_SECONDS saniye icinde inactive + PID=0 dogrulanamadi; ek bir zorla sonlandirma uygulanmadi."
akis_diagnostics_hint
exit 1
