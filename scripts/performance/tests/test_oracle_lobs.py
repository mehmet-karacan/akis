import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dit_oracle import JdbcCursor


class MaterializedResult:
    def __init__(self, value):
        self.value = value

    def getObject(self, _index):
        raise AssertionError('LOB locators must not be created/freed per cell')

    def getString(self, _index):
        return self.value

    def getNString(self, _index):
        return self.value

    def getBytes(self, _index):
        return self.value


class OracleLobTests(unittest.TestCase):
    def test_materialized_lobs_preserve_unicode_empty_and_null_without_locator_rpcs(self):
        for code, kind, values in [(2005, 'CLOB', [None, '', 'a' * 3000]),
                                   (2011, 'NCLOB', [None, '', 'İşık çğöşü']),
                                   (2004, 'BLOB', [None, b'', b'\x00\xff\x80'])]:
            for value in values:
                with self.subTest(kind=kind, value_length=None if value is None else len(value)):
                    cursor = JdbcCursor(SimpleNamespace())
                    cursor.result = MaterializedResult(value)
                    self.assertEqual(cursor._value(1, code, kind), value)


if __name__ == '__main__':
    unittest.main()
