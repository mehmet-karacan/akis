"""Local registry access. Credentials never enter report dictionaries or reprs."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

import oracledb
import psycopg
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_KEY = "akis-local-development-credential-key"


def read_environment(path: Path = ROOT / ".env", overrides=None) -> dict:
    result = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key.strip()):
                continue
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            result[key.strip()] = value
    result.update(os.environ if overrides is None else overrides)
    return result


def encrypt_credential(username: str, password: str, key: str) -> str:
    nonce = os.urandom(12)
    raw = json.dumps({"username": username, "password": password}, ensure_ascii=False).encode()
    encrypted = AESGCM(hashlib.sha256(key.encode()).digest()).encrypt(nonce, raw, None)
    return base64.b64encode(nonce + encrypted).decode("ascii")


def decrypt_credential(encoded: str, key: str) -> tuple[str | None, str]:
    try:
        raw = base64.b64decode(encoded, validate=True)
        plain = AESGCM(hashlib.sha256(key.encode()).digest()).decrypt(raw[:12], raw[12:], None).decode()
        if plain.startswith("{"):
            envelope = json.loads(plain)
            if not isinstance(envelope.get("password"), str):
                raise ValueError()
            return envelope.get("username"), envelope["password"]
        return None, plain
    except Exception:
        raise ValueError("Kayıtlı bağlantının kimlik bilgisi çözülemedi; AKIS_CREDENTIAL_KEY değerini kontrol edin.") from None


@dataclass
class Profile:
    code: str
    provider: str
    host: str
    port: int
    username: str
    password: str = field(repr=False)
    service: str | None = None
    sid: str | None = None
    database: str | None = None
    uuid: str = ""
    fetch_size: int = 1000
    batch_size: int = 1000

    def public(self) -> dict:
        return {"connectionCode": self.code, "provider": self.provider, "host": self.host,
                "port": self.port, "service": self.service, "sid": self.sid,
                "database": self.database, "connectionUuid": self.uuid}

    def oracle_dsn(self) -> str:
        if bool(self.service) == bool(self.sid):
            raise ValueError("Oracle bağlantısında service veya SID alanlarından tam biri gerekir.")
        if self.service:
            return f"{self.host}:{self.port}/{self.service}"
        return oracledb.makedsn(self.host, self.port, sid=self.sid)


def metadata_connection(env=None):
    env = read_environment() if env is None else env
    url = env.get("AKIS_DB_URL", f"jdbc:postgresql://127.0.0.1:{env.get('POSTGRES_PORT','5432')}/{env.get('POSTGRES_DB','akis_metadata')}")
    parts = urlsplit(url.removeprefix("jdbc:"))
    if parts.scheme != "postgresql" or not parts.hostname or not parts.path.strip("/"):
        raise ValueError("Yerel metadata JDBC PostgreSQL adresi geçersiz.")
    return psycopg.connect(host=parts.hostname, port=parts.port or 5432, dbname=parts.path.strip("/"),
                           user=env.get("AKIS_DB_USERNAME", env.get("POSTGRES_USER")),
                           password=env.get("AKIS_DB_PASSWORD", env.get("POSTGRES_PASSWORD")),
                           connect_timeout=8, autocommit=True, row_factory=dict_row,
                           application_name="sky-dit-perf-metadata", options="-c statement_timeout=15000")


def get_profile(metadata, code: str, env=None) -> Profile:
    row = metadata.execute("""select kod,saglayici_turu,sunucu_adi,port,kullanici_adi,sifre,servis_adi,sid,
                         veritabani_adi,uuid,getirme_boyutu,toplu_guncelleme_boyutu
                         from akis.baglanti where kod=%s and durum='ETKIN' and baglanti_modu='JDBC'""", (code,)).fetchone()
    if not row:
        raise ValueError(f"Etkin kayıtlı JDBC bağlantısı bulunamadı: {code}")
    env = read_environment() if env is None else env
    username, password = decrypt_credential(row["sifre"], env.get("AKIS_CREDENTIAL_KEY", DEFAULT_KEY))
    return Profile(row["kod"], row["saglayici_turu"], row["sunucu_adi"], row["port"],
                   username or row["kullanici_adi"], password, row["servis_adi"], row["sid"], row["veritabani_adi"],
                   str(row["uuid"]), row["getirme_boyutu"], row["toplu_guncelleme_boyutu"])


def open_profile(profile: Profile, *, read_only=False):
    if profile.provider == "ORACLE":
        env = read_environment()
        if env.get("SKY_DIT_ORACLE_TRANSPORT", "jdbc") == "jdbc":
            from dit_oracle import open_jdbc
            return open_jdbc(profile, read_only=read_only)
        if env.get("SKY_DIT_ORACLE_CLIENT_DIR") and oracledb.is_thin_mode():
            oracledb.init_oracle_client(lib_dir=env["SKY_DIT_ORACLE_CLIENT_DIR"])
        conn = oracledb.connect(user=profile.username, password=profile.password,
                                dsn=profile.oracle_dsn(), tcp_connect_timeout=8)
        conn.call_timeout = 15000
        conn.module = "SKY_DIT_PERF"
        # SQL SELECTs used by inventory/monitor do not write; prepare/seed explicitly opt into writes.
        return conn
    if profile.provider == "POSTGRESQL":
        conn = psycopg.connect(host=profile.host, port=profile.port, dbname=profile.database,
                               user=profile.username, password=profile.password, connect_timeout=8,
                               autocommit=True, application_name="sky-dit-perf", options="-c statement_timeout=15000")
        if read_only:
            conn.execute("set default_transaction_read_only=on")
        return conn
    raise ValueError("Yalnız Oracle ve PostgreSQL profilleri desteklenir.")


def register_uat(metadata, *, host, port, service, username, password, env=None):
    """Insert only. An existing code must have the same identity; never overwrite PROD or secrets."""
    env = read_environment() if env is None else env
    code = "SKY_UAT"
    existing = metadata.execute("select sunucu_adi,port,servis_adi,kullanici_adi from akis.baglanti where kod=%s", (code,)).fetchone()
    if existing and (existing["sunucu_adi"], existing["port"], existing["servis_adi"], existing["kullanici_adi"]) != (host, port, service, username):
        raise ValueError("SKY_UAT kodu farklı bir bağlantıya ait; üzerine yazılmadı.")
    with metadata.transaction():
        if not existing:
            metadata.execute("""insert into akis.baglanti(kod,ad,aciklama,saglayici_turu,surucu_sinifi,sunucu_adi,port,
                 servis_adi,kullanici_adi,sifre,getirme_boyutu,toplu_guncelleme_boyutu,sorgu_zaman_asimi_saniye)
                 values (%s,'SKY UAT','SKY - DIT performans test bağlantısı','ORACLE','oracle.jdbc.OracleDriver',%s,%s,%s,%s,%s,1000,1000,300)""",
                 (code, host, port, service, username, encrypt_credential(username, password, env.get("AKIS_CREDENTIAL_KEY", DEFAULT_KEY))))
        metadata.execute("""insert into akis.ortam(kod,ad,aciklama,risk,uretim_mi) values ('UAT','SKY UAT','SKY - DIT test ortamı','DUSUK',false)
                         on conflict (kod) do nothing""")
        metadata.execute("""insert into akis.fiziksel_sema(baglanti_id,kod,ad,saglayici_turu,sema_adi,calisma_sema_adi)
             select b.id,'SKY_UAT_TTBP','SKY UAT TTBP','ORACLE',%s,%s from akis.baglanti b where b.kod=%s
             and not exists(select 1 from akis.fiziksel_sema f where f.baglanti_id=b.id and f.sema_adi=%s)""",
             (username, username, code, username))
    return get_profile(metadata, code, env)
