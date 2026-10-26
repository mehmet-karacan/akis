"""Offline contract tests: python -m unittest discover -s scripts/performance/tests -p test_schema.py."""

import hashlib
import math
import struct
import unittest
from dataclasses import FrozenInstanceError, fields, is_dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, localcontext
from scripts.performance import dit_schema as schema


class SchemaTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(hasattr(schema, "COLUMNS"), "schema module must export COLUMNS")

    def row(self, **values):
        return [values.get(column.name) for column in schema.COLUMNS]

    def test_fifty_unique_immutable_columns_and_exact_mapping(self):
        self.assertEqual(len(schema.COLUMNS), 50)
        self.assertEqual(len({column.name for column in schema.COLUMNS}), 50)
        self.assertTrue(is_dataclass(schema.Column))
        self.assertEqual([field.name for field in fields(schema.Column)],
                         ["name", "oracle_type", "postgres_type", "oracle_expression", "nullable"])
        first = schema.COLUMNS[0]
        self.assertEqual((first.name, first.oracle_type, first.postgres_type, first.nullable),
                         ("ID", "NUMBER(12)", "NUMERIC(12,0)", False))
        with self.assertRaises(FrozenInstanceError):
            first.name = "BROKEN"
        types = {column.oracle_type for column in schema.COLUMNS}
        for required in ("NUMBER(38)", "NUMBER(38,10)", "NUMBER(12,-2)",
                         "BINARY_FLOAT", "BINARY_DOUBLE", "DATE", "TIMESTAMP(6)",
                         "TIMESTAMP(6) WITH TIME ZONE", "VARCHAR2(120 CHAR)",
                         "CHAR(8 CHAR)", "NCHAR(8)", "NVARCHAR2(120)", "CLOB", "NCLOB",
                         "RAW(32)", "BLOB"):
            self.assertIn(required, types)

    def test_identifiers_normalize_and_reject_injection_and_11g_overlength(self):
        self.assertEqual(schema.identifier("dit_test_01"), '"DIT_TEST_01"')
        self.assertEqual(schema.identifier("Sky$dit#1"), '"SKY$DIT#1"')
        self.assertEqual(schema.identifier("a" * 30), '"' + "A" * 30 + '"')
        for bad in ("", "a" * 31, "1x", "x.y", 'x"', "x;DROP TABLE y", "a b",
                    "şema", " x", None, 123):
            with self.subTest(bad=bad), self.assertRaises((TypeError, ValueError)):
                schema.identifier(bad)

    def test_ddls_match_column_order_nullability_and_primary_key(self):
        for ddl in (schema.oracle_ddl("sky", "dit_test"),
                    schema.postgres_ddl("sky", "dit_test")):
            self.assertTrue(ddl.startswith('CREATE TABLE "SKY"."DIT_TEST" (\n'))
            self.assertIn('"ID" NUM', ddl)
            self.assertIn('PRIMARY KEY ("ID")', ddl)
            self.assertEqual(ddl.count('"ID"'), 2)
            positions = [ddl.index('"' + c.name + '"') for c in schema.COLUMNS]
            self.assertEqual(positions, sorted(positions))
            self.assertNotIn("NOLOGGING", ddl.upper())
            self.assertNotIn("IDENTITY", ddl.upper())
        oracle = schema.oracle_ddl("sky", "dit_test")
        postgres = schema.postgres_ddl("sky", "dit_test")
        self.assertIn('"ID" NUMBER(12) NOT NULL', oracle)
        self.assertIn('"NUM_PRECISE" NUMERIC(38,10) NOT NULL', postgres)
        self.assertIn('"NUM_SCALED" NUMERIC(14,0) NOT NULL', postgres)
        self.assertIn('"BLOB_BYTES" BYTEA NOT NULL', postgres)
        self.assertIn('"PAYLOAD_JSON" TEXT NOT NULL', postgres)
        self.assertIn('"VARCHAR_EMPTY" VARCHAR2(20 CHAR),', oracle)

    def test_seed_sql_uses_binds_deterministic_expressions_and_bounded_range(self):
        sql = schema.oracle_seed_sql("sky", "dit_test")
        self.assertTrue(sql.startswith('INSERT INTO "SKY"."DIT_TEST" ("ID", '))
        self.assertIn("SELECT :start_id + LEVEL - 1 AS n FROM dual", sql)
        self.assertIn("CONNECT BY LEVEL <= :row_count", sql)
        self.assertIn(":start_id + :row_count - 1 <= 5000000", sql)
        self.assertIn(":row_count > 0", sql)
        self.assertIn(":start_id >= 1", sql)
        self.assertIn(":row_count = TRUNC(:row_count)", sql)
        self.assertIn(":start_id = TRUNC(:start_id)", sql)
        self.assertTrue(sql.isascii(), "Oracle seed SQL must survive non-UTF8 clients")
        self.assertIn(r"UNISTR('\0130\015F\0131k \00E7\011F\00F6\015F\00FC')", sql)
        self.assertIn("EMPTY_CLOB()", sql)
        self.assertIn("EMPTY_BLOB()", sql)
        self.assertIn("HEXTORAW(", sql)
        self.assertIn("FROM_TZ(", sql)
        self.assertIn("TO_CLOB(", sql)
        for unsafe in ("/*+", "APPEND", "PARALLEL", "NOLOGGING", "DBMS_RANDOM", "SYSDATE",
                       "SYSTIMESTAMP", "JSON_OBJECT", "BOOLEAN"):
            self.assertNotIn(unsafe, sql.upper())

    def test_source_select_projects_all_uppercase_columns_and_validates_rows(self):
        sql = schema.source_select("sky", "dit_test", 100000)
        self.assertTrue(sql.startswith('SELECT "ID", "NUM_SMALL", '))
        self.assertTrue(sql.endswith('FROM "SKY"."DIT_TEST" WHERE "ID" BETWEEN 1 AND 100000 ORDER BY "ID"'))
        self.assertIn('AND 5000000 ORDER BY "ID"', schema.source_select("sky", "dit_test", 5000000))
        for bad in (0, -1, 5000001, True, 1.5, "100000", None):
            with self.subTest(bad=bad), self.assertRaises((TypeError, ValueError)):
                schema.source_select("sky", "dit_test", bad)
        for fn, args in ((schema.oracle_ddl, ("bad.owner", "test")),
                         (schema.postgres_ddl, ("sky", 'test"')),
                         (schema.oracle_seed_sql, ("sky;drop", "test")),
                         (schema.source_select, ("sky", "a.b", 10))):
            with self.assertRaises(ValueError):
                fn(*args)

    def test_canonical_framing_and_null_empty_distinction(self):
        self.assertEqual(schema.canonical_row([None] * 50), b"R50:" + b"N0:" * 50)
        actual = schema.canonical_row(self.row(ID=12, VARCHAR_ASCII="A:|", BLOB_BYTES=b"\x00\xff"))
        self.assertTrue(actual.startswith(b"R50:D2:12N0:"))
        self.assertIn(b"S3:A:|", actual)
        self.assertIn(b"B2:\x00\xff", actual)
        for name, empty in (("VARCHAR_EMPTY", ""), ("TEXT_EMPTY", ""), ("BLOB_EMPTY", b"")):
            self.assertNotEqual(schema.canonical_row(self.row()), schema.canonical_row(self.row(**{name: empty})))
        with self.assertRaises(ValueError):
            schema.canonical_row([None] * 49)

    def test_decimal_canonicalization_never_rounds_precision_38(self):
        with localcontext() as context:
            context.prec = 6
            a = schema.canonical_row(self.row(NUM_MAX38=Decimal("99999999999999999999999999999999999999")))
            b = schema.canonical_row(self.row(NUM_MAX38=99999999999999999999999999999999999999))
            self.assertEqual(a, b)
            self.assertIn(b"D38:99999999999999999999999999999999999999", a)
        self.assertEqual(schema.canonical_row(self.row(NUM_DECIMAL=Decimal("-12.340000"))),
                         schema.canonical_row(self.row(NUM_DECIMAL=Decimal("-12.34"))))
        self.assertEqual(schema.canonical_row(self.row(NUM_ZERO=Decimal("-0.00"))),
                         schema.canonical_row(self.row(NUM_ZERO=0)))
        self.assertIn(b"D4:1000", schema.canonical_row(self.row(NUM_BIG=Decimal("1E+3"))))
        for bad in (float("nan"), Decimal("Infinity"), True):
            with self.assertRaises((TypeError, ValueError)):
                schema.canonical_row(self.row(NUM_BIG=bad))

    def test_binary32_matches_driver_rounding_and_keeps_binary64_precision(self):
        widened = struct.unpack("!f", struct.pack("!f", 0.1))[0]
        self.assertEqual(schema.canonical_row(self.row(FLOAT32=0.1)),
                         schema.canonical_row(self.row(FLOAT32=widened)))
        self.assertIn(b"F4:\x3d\xcc\xcc\xcd", schema.canonical_row(self.row(FLOAT32=0.1)))
        self.assertNotEqual(schema.canonical_row(self.row(FLOAT64=0.1)),
                            schema.canonical_row(self.row(FLOAT64=math.nextafter(0.1, 1))))
        self.assertEqual(schema.canonical_row(self.row(FLOAT32=-0.0)),
                         schema.canonical_row(self.row(FLOAT32=0.0)))
        for bad in (float("nan"), float("inf"), 1e100):
            with self.assertRaises(ValueError):
                schema.canonical_row(self.row(FLOAT32=bad))

    def test_binary32_underflow_normalizes_zero_after_rounding(self):
        self.assertEqual(schema.canonical_row(self.row(FLOAT32=-1e-100)),
                         schema.canonical_row(self.row(FLOAT32=0.0)))

    def test_numbers_reject_lossy_driver_floats_and_text(self):
        for bad in (0.1, 1e37, "99999999999999999999999999999999999999"):
            with self.subTest(bad=bad), self.assertRaises(TypeError):
                schema.canonical_row(self.row(NUM_MAX38=bad))

    def test_timestamp_normalization_seconds_fraction_and_date_midnight(self):
        local = datetime(2024, 1, 2, 3, 4, 5, 123456, timezone(timedelta(hours=3)))
        utc = datetime(2024, 1, 2, 0, 4, 5, 123456, timezone.utc)
        self.assertEqual(schema.canonical_row(self.row(TS_TZ=local)), schema.canonical_row(self.row(TS_TZ=utc)))
        self.assertIn(b"Z27:2024-01-02T00:04:05.123456Z", schema.canonical_row(self.row(TS_TZ=local)))
        self.assertEqual(schema.canonical_row(self.row(DATE_VALUE=date(2024, 1, 2))),
                         schema.canonical_row(self.row(DATE_VALUE=datetime(2024, 1, 2))))
        # DATE has exact seconds; fractional DATE values are invalid, not silently rounded.
        self.assertNotEqual(schema.canonical_row(self.row(DATE_VALUE=datetime(2024, 1, 2, 0, 0, 1))),
                            schema.canonical_row(self.row(DATE_VALUE=date(2024, 1, 2))))
        self.assertNotEqual(schema.canonical_row(self.row(TS_VALUE=datetime(2024, 1, 2, microsecond=1))),
                            schema.canonical_row(self.row(TS_VALUE=datetime(2024, 1, 2))))
        for values in ({"TS_TZ": datetime(2024, 1, 2)}, {"TS_VALUE": utc},
                       {"DATE_VALUE": datetime(2024, 1, 2, microsecond=1)}):
            with self.assertRaises(ValueError):
                schema.canonical_row(self.row(**values))

    def test_lobs_binary_buffers_unicode_and_char_padding(self):
        class Lob:
            def __init__(self, value):
                self.value = value

            def read(self):
                return self.value

        source = self.row(TEXT_SHORT=Lob("İşık çğöşü"), NTEXT=Lob("日本語"),
                          BLOB_BYTES=Lob(b"\x00\xff"), RAW_BYTES=bytearray(b"\x01\x02"),
                          CHAR_PADDED="X       ")
        target = self.row(TEXT_SHORT="İşık çğöşü", NTEXT="日本語",
                          BLOB_BYTES=memoryview(b"\x00\xff"), RAW_BYTES=b"\x01\x02",
                          CHAR_PADDED="X")
        self.assertEqual(schema.canonical_row(source), schema.canonical_row(target))
        self.assertNotEqual(schema.canonical_row(self.row(VARCHAR_SPACES="X ")),
                            schema.canonical_row(self.row(VARCHAR_SPACES="X")))
        for values in ({"TEXT_SHORT": b"utf8"}, {"BLOB_BYTES": "ff"}, {"FLOAT64": "0.1"}):
            with self.assertRaises(TypeError):
                schema.canonical_row(self.row(**values))

    def test_all_fifty_mixed_values_contribute_to_digest(self):
        # A full driver-shaped row, covering every ordinal (not only sparse cases).
        stamp = datetime(2024, 2, 29, 23, 59, 59, 999999)
        row = [
            1, Decimal("1"), 7919, Decimal("12345678901234567890123456789001"),
            Decimal("1.000001"), Decimal("-1.0001"),
            Decimal("1234567890123456789012345678.0000010000"),
            Decimal("12300"), Decimal("-0.001"), 1,
            0.1, 1 / 13, -0.1, 1e-100,
            datetime(2024, 2, 29, 23, 59, 59), datetime(2024, 3, 1),
            stamp, stamp, stamp.replace(tzinfo=timezone.utc), stamp.replace(tzinfo=timezone.utc),
            "row-0000001", "İşık çğöşü-0000001", "value-0000001", None,
            "C0000001", "X       ", "İ       ", "İşık çğöşü", "İşık çğöşü",
            "clob-0000001", "x" * 3000, "optional-0000001", "", "İşık çğöşü" * 30,
            "İşık çğöşü", b"\x00\x7f\xff\x80\x00\x01", b"\x00\x01",
            b"\xab" * 256 + b"\x00\x01", b"\x00\xff\x00\x01", b"",
            '{"id":1,"label":"İşık çğöşü","active":true,"optional":null}',
            Decimal("0.000000001"), Decimal("0.00"),
            Decimal("99999999999999999999999999999999999998"),
            Decimal("-99999999999999999999999999999999999998"),
            "  row 0000001  ", "O'Reilly | : 0000001", datetime(2000, 1, 1),
            datetime(2000, 2, 29, 23, 59, 59, 1), b"\x00\x01\x02\x03\x7f\x80\xfe\xff",
        ]
        self.assertEqual(len(row), 50)
        baseline = schema.canonical_row(row)
        for index, column in enumerate(schema.COLUMNS):
            changed = row.copy()
            changed[index] = "" if row[index] is None else None
            with self.subTest(column=column.name):
                self.assertNotEqual(schema.canonical_row(changed), baseline)

    def test_timezone_fractional_offsets_cross_day_without_losing_microseconds(self):
        local = datetime(2024, 1, 1, 0, 0, 0, 1, timezone(timedelta(hours=5, minutes=45)))
        actual = schema.canonical_row(self.row(TS_TZ=local))
        self.assertIn(b"Z27:2023-12-31T18:15:00.000001Z", actual)
        previous_day = datetime(2023, 12, 31, 14, 45, 0, 1,
                                timezone(timedelta(hours=-3, minutes=-30)))
        self.assertEqual(actual, schema.canonical_row(self.row(TS_TZ=previous_day)))

    def test_streaming_digest_is_framed_ordered_and_single_pass(self):
        rows = [self.row(ID=1), self.row(ID=2)]
        expected = hashlib.sha256(b"R50:D1:1" + b"N0:" * 49 + b"R50:D1:2" + b"N0:" * 49).hexdigest()
        self.assertEqual(schema.streaming_digest(iter(rows)), expected)
        self.assertEqual(schema.streaming_digest(iter(())), hashlib.sha256(b"").hexdigest())
        self.assertNotEqual(schema.streaming_digest(iter(rows)), schema.streaming_digest(reversed(rows)))


if __name__ == "__main__":
    unittest.main()
