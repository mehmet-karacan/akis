"""Offline schema contract for the SKY DIT Oracle -> PostgreSQL benchmark.

No drivers, connections, credentials, or side effects are required. SQL is for
Oracle 11g+ and PostgreSQL; execute the returned statements without a SQL-client
terminator. Tables and columns use quoted uppercase names (including the schema
argument). The caller owns schema creation, transactions, batching and bind
validation. Seed batches use integer ``start_id`` / ``row_count`` binds, with
100,000 through 5,000,000 rows typical for a complete run. Small batches are legal.

Fetch Oracle NUMBER as Decimal (or exact integers), NOT binary float, especially
for precision 38. Fetch DATE as datetime: PostgreSQL DATE would lose its seconds,
so the target is TIMESTAMP(0). TIMESTAMP(6) comparisons are exact to microseconds;
there is no temporal tolerance. WITH TIME ZONE values must be aware and compare
as UTC instants. Fixed CHAR/NCHAR ignore right ASCII-space padding; other text,
including JSON CLOB text, compares byte-for-byte after UTF-8 encoding. Oracle's
stored empty VARCHAR2 is NULL; an empty LOB remains distinct from NULL. No JSON
parsing or Unicode normalization hides content differences.

``canonical_row`` accepts 50 positional values in COLUMNS order, including LOB
objects exposing read(), bytes, bytearray and memoryview. Framing is R50: followed
by 50 tag/decimal-byte-length/colon/payload fields. Tags: N=null, D=decimal,
F=binary32, G=binary64, T=naive time, Z=UTC time, S=text, B=bytes. Binary floats
must be finite; signed zero compares equal. ``streaming_digest`` returns the
SHA-256 hex digest of these frames, in input order, with one row in memory.

All generated LOBs are <4096 bytes: the largest is 3000 ASCII bytes. National
text uses at most 300 characters (<=1200 UTF-8 bytes); BLOBs use <=258 bytes.
"""

from dataclasses import dataclass
from datetime import date, datetime, time, timezone
from decimal import Decimal
import hashlib
import math
import re
import struct
from typing import Iterable, Sequence


__all__ = ["Column", "COLUMNS", "identifier", "oracle_ddl", "postgres_ddl",
           "oracle_seed_sql", "source_select", "canonical_row", "streaming_digest"]


@dataclass(frozen=True)
class Column:
    name: str
    oracle_type: str
    postgres_type: str
    oracle_expression: str
    nullable: bool


# Only ASCII appears in emitted SQL, even when the stored value is Turkish.
_TURKISH = r"UNISTR('\0130\015F\0131k \00E7\011F\00F6\015F\00FC')"
_N_TEXT = "TO_CHAR(n, 'FM0000000', 'NLS_NUMERIC_CHARACTERS=''.,''')"
_HEX = "LPAD(TO_CHAR(MOD(n,65536), 'FMXXXX'), 4, '0')"
_DATE = "DATE '2020-01-01' + MOD(n,31536000)/86400"
_TS = ("TIMESTAMP '2020-01-01 00:00:00' + "
       "NUMTODSINTERVAL(MOD(n,31536000) + MOD(n,1000000)/1000000, 'SECOND')")
_TZ = "FROM_TZ((" + _TS + "), CASE WHEN MOD(n,2)=0 THEN '+03:00' ELSE '-05:00' END)"


COLUMNS: Sequence[Column] = (
    Column("ID", "NUMBER(12)", "NUMERIC(12,0)", "n", False),
    Column("NUM_SMALL", "NUMBER(5)", "NUMERIC(5,0)", "MOD(n,100000)", False),
    Column("NUM_INT", "NUMBER(12)", "NUMERIC(12,0)", "n * 7919", False),
    Column("NUM_BIG", "NUMBER(38)", "NUMERIC(38,0)", "12345678901234567890123456789000 + n", False),
    Column("NUM_DECIMAL", "NUMBER(18,6)", "NUMERIC(18,6)", "n + MOD(n,1000000)/1000000", False),
    Column("NUM_NEGATIVE", "NUMBER(18,4)", "NUMERIC(18,4)", "-n - MOD(n,10000)/10000", False),
    Column("NUM_PRECISE", "NUMBER(38,10)", "NUMERIC(38,10)", "1234567890123456789012345678 + MOD(n,1000000)/1000000", False),
    # Oracle negative scale rounds to hundreds; use a portable PG integer scale.
    Column("NUM_SCALED", "NUMBER(12,-2)", "NUMERIC(14,0)", "ROUND(n * 12345,-2)", False),
    Column("NUM_NULLABLE", "NUMBER(16,3)", "NUMERIC(16,3)", "CASE WHEN MOD(n,7)=0 THEN NULL ELSE -n/1000 END", True),
    Column("NUM_FLAG", "NUMBER(1)", "NUMERIC(1,0)", "MOD(n,2)", False),
    Column("FLOAT32", "BINARY_FLOAT", "REAL", "CAST(MOD(n,100003)/7 AS BINARY_FLOAT)", False),
    Column("FLOAT64", "BINARY_DOUBLE", "DOUBLE PRECISION", "CAST(n/13 AS BINARY_DOUBLE)", False),
    Column("FLOAT32_NEG", "BINARY_FLOAT", "REAL", "CAST(-MOD(n,100003)/13 AS BINARY_FLOAT)", False),
    Column("FLOAT64_SMALL", "BINARY_DOUBLE", "DOUBLE PRECISION", "CAST((MOD(n,997)+1)/1e100 AS BINARY_DOUBLE)", False),
    Column("DATE_VALUE", "DATE", "TIMESTAMP(0) WITHOUT TIME ZONE", _DATE, False),
    Column("DATE_NULLABLE", "DATE", "TIMESTAMP(0) WITHOUT TIME ZONE", "CASE WHEN MOD(n,11)=0 THEN NULL ELSE " + _DATE + " END", True),
    Column("TS_VALUE", "TIMESTAMP(6)", "TIMESTAMP(6) WITHOUT TIME ZONE", _TS, False),
    Column("TS_NULLABLE", "TIMESTAMP(6)", "TIMESTAMP(6) WITHOUT TIME ZONE", "CASE WHEN MOD(n,13)=0 THEN NULL ELSE " + _TS + " END", True),
    Column("TS_TZ", "TIMESTAMP(6) WITH TIME ZONE", "TIMESTAMP(6) WITH TIME ZONE", _TZ, False),
    Column("TS_TZ_NULLABLE", "TIMESTAMP(6) WITH TIME ZONE", "TIMESTAMP(6) WITH TIME ZONE", "CASE WHEN MOD(n,7)=0 THEN NULL ELSE " + _TZ + " END", True),
    Column("VARCHAR_ASCII", "VARCHAR2(80 CHAR)", "VARCHAR(80)", "'row-' || " + _N_TEXT, False),
    Column("VARCHAR_UNICODE", "VARCHAR2(120 CHAR)", "VARCHAR(120)", _TURKISH + " || '-' || " + _N_TEXT, False),
    Column("VARCHAR_NULLABLE", "VARCHAR2(80 CHAR)", "VARCHAR(80)", "CASE WHEN MOD(n,7)=0 THEN NULL ELSE 'value-' || " + _N_TEXT + " END", True),
    # Oracle treats '' as NULL, deliberately unlike EMPTY_CLOB()/EMPTY_BLOB().
    Column("VARCHAR_EMPTY", "VARCHAR2(20 CHAR)", "VARCHAR(20)", "''", True),
    Column("CHAR_CODE", "CHAR(8 CHAR)", "CHAR(8)", "'C' || " + _N_TEXT, False),
    Column("CHAR_PADDED", "CHAR(8 CHAR)", "CHAR(8)", "'X'", False),
    Column("NCHAR_CODE", "NCHAR(8)", "CHAR(8)", r"UNISTR('\0130')", False),
    Column("NVARCHAR_TEXT", "NVARCHAR2(120)", "VARCHAR(120)", _TURKISH + " || '-' || " + _N_TEXT, False),
    Column("NVARCHAR_NULLABLE", "NVARCHAR2(120)", "VARCHAR(120)", "CASE WHEN MOD(n,11)=0 THEN NULL ELSE " + _TURKISH + " END", True),
    Column("TEXT_SHORT", "CLOB", "TEXT", "TO_CLOB('clob-' || " + _N_TEXT + ")", False),
    Column("TEXT_LONG", "CLOB", "TEXT", "TO_CLOB(RPAD('row-' || " + _N_TEXT + ",3000,'x'))", False),
    Column("TEXT_NULLABLE", "CLOB", "TEXT", "TO_CLOB(CASE WHEN MOD(n,13)=0 THEN NULL ELSE 'optional-' || " + _N_TEXT + " END)", True),
    Column("TEXT_EMPTY", "CLOB", "TEXT", "EMPTY_CLOB()", False),
    Column("NTEXT", "NCLOB", "TEXT", "TO_NCLOB(RPAD(" + _TURKISH + ",300," + _TURKISH + "))", False),
    Column("NTEXT_NULLABLE", "NCLOB", "TEXT", "TO_NCLOB(CASE WHEN MOD(n,7)=0 THEN NULL ELSE " + _TURKISH + " END)", True),
    Column("RAW_BYTES", "RAW(32)", "BYTEA", "HEXTORAW('007FFF80' || " + _HEX + ")", False),
    Column("RAW_NULLABLE", "RAW(32)", "BYTEA", "HEXTORAW(CASE WHEN MOD(n,11)=0 THEN NULL ELSE " + _HEX + " END)", True),
    Column("BLOB_BYTES", "BLOB", "BYTEA", "TO_BLOB(HEXTORAW(RPAD('AB',512,'AB') || " + _HEX + "))", False),
    Column("BLOB_NULLABLE", "BLOB", "BYTEA", "TO_BLOB(HEXTORAW(CASE WHEN MOD(n,13)=0 THEN NULL ELSE '00FF' || " + _HEX + " END))", True),
    Column("BLOB_EMPTY", "BLOB", "BYTEA", "EMPTY_BLOB()", False),
    Column("PAYLOAD_JSON", "CLOB", "TEXT", "TO_CLOB('{\"id\":' || TO_CHAR(n,'FM9999990') || ',\"label\":\"' || " + _TURKISH + " || '\",\"active\":true,\"optional\":null}')", False),
    Column("NUM_RATIO", "NUMBER(20,9)", "NUMERIC(20,9)", "MOD(n,1000000)/1000000000", False),
    Column("NUM_ZERO", "NUMBER(10,2)", "NUMERIC(10,2)", "0", False),
    Column("NUM_MAX38", "NUMBER(38)", "NUMERIC(38,0)", "99999999999999999999999999999999999999 - n", False),
    Column("NUM_MIN38", "NUMBER(38)", "NUMERIC(38,0)", "-99999999999999999999999999999999999999 + n", False),
    Column("VARCHAR_SPACES", "VARCHAR2(40 CHAR)", "VARCHAR(40)", "'  row ' || " + _N_TEXT + " || '  '", False),
    Column("VARCHAR_QUOTES", "VARCHAR2(80 CHAR)", "VARCHAR(80)", "'O''Reilly | : ' || " + _N_TEXT, False),
    Column("DATE_BOUNDARY", "DATE", "TIMESTAMP(0) WITHOUT TIME ZONE", "DATE '1999-12-31' + (86399 + MOD(n,3))/86400", False),
    Column("TS_FRACTION", "TIMESTAMP(6)", "TIMESTAMP(6) WITHOUT TIME ZONE", "TIMESTAMP '2000-02-29 23:59:59' + NUMTODSINTERVAL(MOD(n,1000000)/1000000,'SECOND')", False),
    Column("RAW_EDGE", "RAW(16)", "BYTEA", "HEXTORAW('000102037F80FEFF')", False),
)


def identifier(name: str) -> str:
    """Validate one unquoted ASCII Oracle 11g identifier, uppercase and quote it.

    The 30-byte limit is intentional for Oracle 11g compatibility. Qualified names
    and already quoted names are rejected; pass owner/table separately.
    """
    if not isinstance(name, str):
        raise TypeError("identifier must be a string")
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9_$#]{0,29}", name, flags=re.ASCII) is None:
        raise ValueError("identifier must be 1..30 ASCII characters, starting with a letter")
    return '"' + name.upper() + '"'


def _ddl(namespace: str, table: str, oracle: bool) -> str:
    target = identifier(namespace) + "." + identifier(table)
    definitions = ["  " + identifier(c.name) + " " +
                   (c.oracle_type if oracle else c.postgres_type) +
                   ("" if c.nullable else " NOT NULL") for c in COLUMNS]
    definitions.append('  PRIMARY KEY ("ID")')
    return "CREATE TABLE " + target + " (\n" + ",\n".join(definitions) + ")" + (" LOGGING" if oracle else "")


def oracle_ddl(owner: str, table: str) -> str:
    """CREATE TABLE with conventional logging, no identity or native JSON."""
    return _ddl(owner, table, True)


def postgres_ddl(schema: str, table: str) -> str:
    """Matching target table; JSON stays TEXT and Oracle DATE keeps seconds."""
    return _ddl(schema, table, False)


def oracle_seed_sql(owner: str, table: str) -> str:
    """Bound INSERT SELECT for deterministic batches within ID 1..5,000,000.

    Invalid numeric ranges insert zero rows, including the CONNECT BY root-row
    case for row_count <= 0. The caller should also reject invalid binds before
    execution. There are no append/parallel hints or session-dependent dates.
    """
    target = identifier(owner) + "." + identifier(table)
    columns = ", ".join(identifier(c.name) for c in COLUMNS)
    expressions = ",\n  ".join(c.oracle_expression for c in COLUMNS)
    return ("INSERT INTO " + target + " (" + columns + ")\nSELECT\n  " + expressions +
            "\nFROM (SELECT :start_id + LEVEL - 1 AS n FROM dual\n"
            "      CONNECT BY LEVEL <= :row_count)\n"
            "WHERE :start_id >= 1 AND :row_count > 0\n"
            "  AND :start_id = TRUNC(:start_id) AND :row_count = TRUNC(:row_count)\n"
            "  AND :start_id + :row_count - 1 <= 5000000")


def source_select(owner: str, table: str, rows: int) -> str:
    """Ordered, explicit projection for IDs 1..rows (1 <= rows <= 5,000,000).

    SQL is also valid on the matching PostgreSQL target using its schema name.
    Fewer than 100k rows are allowed for smoke tests and short batches.
    """
    if isinstance(rows, bool) or not isinstance(rows, int):
        raise TypeError("rows must be an integer")
    if not 1 <= rows <= 5_000_000:
        raise ValueError("rows must be in 1..5000000")
    return ("SELECT " + ", ".join(identifier(c.name) for c in COLUMNS) +
            " FROM " + identifier(owner) + "." + identifier(table) +
            ' WHERE "ID" BETWEEN 1 AND ' + str(rows) + ' ORDER BY "ID"')


def _decimal_bytes(value: object) -> bytes:
    if isinstance(value, bool) or not isinstance(value, (int, Decimal)):
        raise TypeError("NUMBER requires Decimal or int; configure exact Oracle fetching")
    number = Decimal(value)
    if not number.is_finite():
        raise ValueError("NUMBER must be finite")
    if number.is_zero():
        return b"0"
    # Decimal.normalize() rounds under the caller's decimal context; format does not.
    text = format(number, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text.encode("ascii")


def _value_bytes(column: Column, value: object) -> tuple[bytes, bytes]:
    if value is None:
        return b"N", b""
    kind = column.oracle_type
    if kind in ("CLOB", "NCLOB", "BLOB") and callable(getattr(value, "read", None)):
        value = value.read()
    if kind.startswith("NUMBER"):
        return b"D", _decimal_bytes(value)
    if kind in ("BINARY_FLOAT", "BINARY_DOUBLE"):
        if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
            raise TypeError("binary float requires a numeric value")
        try:
            number = float(value)
            if not math.isfinite(number):
                raise ValueError("binary float must be finite")
            wire_format = "!f" if kind == "BINARY_FLOAT" else "!d"
            packed = struct.pack(wire_format, number)
            # Normalize AFTER binary32 rounding, which can underflow to -0.0.
            rounded = struct.unpack(wire_format, packed)[0]
            if not math.isfinite(rounded):
                raise ValueError("binary float outside finite range")
            if rounded == 0:
                packed = struct.pack(wire_format, 0.0)
        except (OverflowError, struct.error) as exc:
            raise ValueError("binary float outside finite range") from exc
        return (b"F" if kind == "BINARY_FLOAT" else b"G"), packed
    if kind == "DATE" or kind.startswith("TIMESTAMP"):
        if kind == "DATE" and isinstance(value, date) and not isinstance(value, datetime):
            value = datetime.combine(value, time())
        if not isinstance(value, datetime):
            raise TypeError("temporal value requires datetime (DATE also accepts date at midnight)")
        aware = value.utcoffset() is not None
        if "WITH TIME ZONE" in kind:
            if not aware:
                raise ValueError("WITH TIME ZONE requires an aware datetime")
            text = value.astimezone(timezone.utc).replace(tzinfo=None).isoformat(timespec="microseconds") + "Z"
            return b"Z", text.encode("ascii")
        if aware:
            raise ValueError("DATE / TIMESTAMP WITHOUT TIME ZONE requires a naive datetime")
        if kind == "DATE" and value.microsecond:
            raise ValueError("Oracle DATE has whole seconds; fractional seconds are invalid")
        return b"T", value.isoformat(timespec="microseconds").encode("ascii")
    if kind == "BLOB" or kind.startswith("RAW("):
        if not isinstance(value, (bytes, bytearray, memoryview)):
            raise TypeError("RAW / BLOB requires bytes or a binary buffer")
        return b"B", bytes(value)
    if not isinstance(value, str):
        raise TypeError("character / CLOB / NCLOB value requires str")
    if kind.startswith(("CHAR(", "NCHAR(")):
        value = value.rstrip(" ")
    return b"S", value.encode("utf-8")


def canonical_row(values: Sequence[object]) -> bytes:
    """Return a lossless, unambiguous typed frame; see module comparison rules.

    NULL is allowed in any position to serialize incomplete rows for diagnostics;
    database nullability and seed row counts must be validated separately.
    """
    if len(values) != len(COLUMNS):
        raise ValueError("canonical_row requires exactly 50 values in COLUMNS order")
    parts = [b"R50:"]
    for column, value in zip(COLUMNS, values):
        tag, payload = _value_bytes(column, value)
        parts.extend((tag, str(len(payload)).encode("ascii"), b":", payload))
    return b"".join(parts)


def streaming_digest(rows: Iterable[Sequence[object]]) -> str:
    """SHA-256 hexdigest of a single pass over ordered rows; does not fetch rows.

    Use source_select for both sides and independently verify expected row counts
    and IDs. This helper detects content/order differences, not missing ranges.
    """
    digest = hashlib.sha256()
    for row in rows:
        digest.update(canonical_row(row))
    return digest.hexdigest()
