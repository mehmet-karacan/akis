#!/bin/bash
# Shared helpers for the SKY-DIT systemd wrappers. Source from the entry scripts.

AKIS_SERVICE='akis.service'
AKIS_HEALTH_URL='http://127.0.0.1:8080/actuator/health'

akis_error() {
    printf 'HATA: %s\n' "$*" >&2
}

akis_require_commands() {
    local name
    for name in "$@"; do
        if ! command -v "$name" >/dev/null 2>&1; then
            akis_error "Gerekli komut bulunamadi: $name"
            return 4
        fi
    done
}

akis_require_root() {
    if [[ $(id -u) != 0 ]]; then
        akis_error 'Bu islem root yetkisi gerektirir. Root oturumunda veya sudo ile calistirin.'
        return 4
    fi
}

akis_validate_wait() {
    if [[ ! $1 =~ ^[1-9][0-9]{0,2}$ ]] || (( $1 > 600 )); then
        akis_error 'Bekleme suresi 1 ile 600 arasinda tam saniye olmalidir.'
        return 2
    fi
}

akis_snapshot() {
    local data key value
    AKIS_LOAD_STATE='unknown'
    AKIS_ACTIVE_STATE='unknown'
    AKIS_SUB_STATE='unknown'
    AKIS_MAIN_PID='0'
    if ! data=$(timeout --foreground 5s systemctl show "$AKIS_SERVICE" \
        --property=LoadState,ActiveState,SubState,MainPID 2>/dev/null); then
        akis_error 'systemd servis bilgisi okunamadi.'
        return 4
    fi
    while IFS='=' read -r key value; do
        case "$key" in
            LoadState) AKIS_LOAD_STATE=$value ;;
            ActiveState) AKIS_ACTIVE_STATE=$value ;;
            SubState) AKIS_SUB_STATE=$value ;;
            MainPID) AKIS_MAIN_PID=$value ;;
        esac
    done <<< "$data"
    if [[ $AKIS_LOAD_STATE != loaded ]]; then
        akis_error "$AKIS_SERVICE yuklenemedi: $AKIS_LOAD_STATE"
        return 4
    fi
}

akis_health_up() {
    local payload max_seconds=${1:-5}
    if ! payload=$(curl --fail --silent --show-error --noproxy '*' \
        --connect-timeout 2 --max-time "$max_seconds" --max-filesize 65536 \
        "$AKIS_HEALTH_URL" 2>/dev/null); then
        return 1
    fi
    # Check the root health status, not an UP value nested under a component.
    printf '%s' "$payload" | python3 -c \
        'import json, sys; d=json.load(sys.stdin); sys.exit(0 if isinstance(d, dict) and d.get("status") == "UP" else 1)' \
        2>/dev/null
}

akis_diagnostics_hint() {
    printf 'Inceleme: systemctl --no-pager --full status %s\n' "$AKIS_SERVICE" >&2
    printf 'Loglar: journalctl -u %s -n 100 --no-pager\n' "$AKIS_SERVICE" >&2
}
