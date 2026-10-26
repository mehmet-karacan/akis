#!/bin/bash
set -Eeuo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=akis-service.sh
source "$SCRIPT_DIR/akis-service.sh"

if [[ ${1:-} == --help && $# == 1 ]]; then
    printf 'Kullanim: %s [bekleme_saniye]\nBackend akis.service baslatilir; health UP beklenir. Varsayilan: 120 saniye.\n' "$0"
    exit 0
fi
if (( $# > 1 )); then
    akis_error 'Kullanim: start.sh [bekleme_saniye]'
    exit 2
fi
WAIT_SECONDS=${1:-120}
akis_validate_wait "$WAIT_SECONDS"
akis_require_commands id systemctl timeout curl python3
akis_require_root
akis_snapshot

if [[ $AKIS_ACTIVE_STATE == active ]]; then
    printf '%s zaten aktif; yeniden baslatilmadan saglik kontrolu yapiliyor.\n' "$AKIS_SERVICE"
else
    printf '%s icin baslatma istegi veriliyor.\n' "$AKIS_SERVICE"
    if ! timeout --foreground 10s systemctl start --no-block "$AKIS_SERVICE"; then
        akis_error 'Baslatma istegi basarisiz veya komut zaman asimina ugradi; servis durumu incelenmeli.'
        akis_diagnostics_hint
        exit 1
    fi
fi

deadline=$((SECONDS + WAIT_SECONDS))
while (( SECONDS < deadline )); do
    akis_snapshot
    if [[ $AKIS_ACTIVE_STATE == failed ]]; then
        akis_error "$AKIS_SERVICE failed durumunda."
        akis_diagnostics_hint
        exit 1
    fi
    remaining=$((deadline - SECONDS))
    probe_seconds=$((remaining < 5 ? remaining : 5))
    if [[ $AKIS_ACTIVE_STATE == active ]] && (( probe_seconds > 0 )) && akis_health_up "$probe_seconds"; then
        printf 'BASARILI: %s active; PID=%s; backend health UP.\n' "$AKIS_SERVICE" "$AKIS_MAIN_PID"
        exit 0
    fi
    if (( SECONDS < deadline )); then sleep 1; fi
done

akis_error "$WAIT_SECONDS saniye icinde active + health UP dogrulanamadi. Servis otomatik durdurulmadi."
akis_diagnostics_hint
exit 1
