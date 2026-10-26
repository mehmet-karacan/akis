"""Small JDBC DB-API adapter for UAT accounts with legacy Oracle verifiers.

The application already uses this JDBC driver. No password goes to subprocess
arguments; the embedded JVM receives properties in memory.
"""
from __future__ import annotations

import os
import re
import threading
from datetime import datetime
from decimal import Decimal
from pathlib import Path

_lock = threading.Lock()


def start_jvm():
    import jpype
    with _lock:
        if not jpype.isJVMStarted():
            configured = os.environ.get("SKY_DIT_ORACLE_JDBC_JAR")
            candidates = ([Path(configured)] if configured else
                          sorted((Path.home()/".m2/repository/com/oracle/database/jdbc/ojdbc17").glob("*/*.jar")))
            jars = [str(p) for p in candidates if p.is_file() and not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
            if not jars:
                raise ValueError("Oracle JDBC jar bulunamadı; SKY_DIT_ORACLE_JDBC_JAR belirtin.")
            i18n_configured = os.environ.get("SKY_DIT_ORACLE_I18N_JAR")
            i18n_candidates = ([Path(i18n_configured)] if i18n_configured else
                               sorted((Path.home()/".m2/repository/com/oracle/database/nls/orai18n").glob("*/*.jar")))
            i18n = [str(p) for p in i18n_candidates if p.is_file() and not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
            if not i18n:
                raise ValueError("Oracle NLS jar bulunamadı; SKY_DIT_ORACLE_I18N_JAR belirtin (Türkçe charset desteği).")
            jpype.startJVM("--enable-native-access=ALL-UNNAMED", classpath=[jars[-1], i18n[-1]], convertStrings=True)
    return jpype


def open_jdbc(profile, read_only=False):
    jpype = start_jvm()
    props = jpype.JClass("java.util.Properties")()
    props.setProperty("user", profile.username)
    props.setProperty("password", profile.password)
    props.setProperty("oracle.net.CONNECT_TIMEOUT", "8000")
    props.setProperty("oracle.jdbc.ReadTimeout", "15000")
    props.setProperty("oracle.jdbc.defaultNChar", "false")
    profile.oracle_dsn()  # Reject ambiguous service/SID before opening.
    url = (f"jdbc:oracle:thin:@//{profile.host}:{profile.port}/{profile.service}" if profile.service else
           f"jdbc:oracle:thin:@{profile.host}:{profile.port}:{profile.sid}")
    jpype.JClass("oracle.jdbc.OracleDriver")
    driver = jpype.JClass("java.sql.DriverManager")
    raw = driver.getConnection(url, props)
    props.clear()
    raw.setAutoCommit(False)
    raw.setReadOnly(read_only)
    return JdbcConnection(raw, jpype)


class JdbcConnection:
    def __init__(self, raw, jpype):
        self.raw, self.jpype = raw, jpype
        self.version = str(raw.getMetaData().getDatabaseProductVersion())

    def cursor(self):
        return JdbcCursor(self)

    def commit(self):
        self.raw.commit()

    def rollback(self):
        self.raw.rollback()

    def close(self):
        if not self.raw.isClosed():
            self.raw.rollback()
            self.raw.close()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()


class JdbcCursor:
    def __init__(self, connection):
        self.connection = connection
        self.arraysize = 1000
        self.statement = None
        self.result = None
        self.description = None
        self.rowcount = -1
        self.types = []

    def execute(self, sql, parameters=None):
        self.close()
        parameters = parameters or ()
        if isinstance(parameters, dict):
            ordered = []
            names = "|".join(re.escape(k) for k in parameters)
            def bind(match):
                ordered.append(parameters[match.group(1)])
                return "?"
            sql = re.sub(r":("+names+r")\b", bind, sql) if names else sql
            parameters = ordered
        elif parameters:
            sql = re.sub(r":\d+\b", "?", sql)
        self.statement = self.connection.raw.prepareStatement(sql)
        self.statement.setFetchSize(self.arraysize)
        self.statement.setQueryTimeout(300)
        for i, value in enumerate(parameters,1):
            if value is None:
                self.statement.setNull(i, 12)
            elif isinstance(value,int):
                self.statement.setLong(i, value)
            elif isinstance(value,float):
                self.statement.setDouble(i, value)
            elif isinstance(value,Decimal):
                self.statement.setBigDecimal(i, self.connection.jpype.JClass("java.math.BigDecimal")(str(value)))
            else:
                self.statement.setString(i, str(value))
        if self.statement.execute():
            self.result = self.statement.getResultSet()
            meta = self.result.getMetaData()
            self.types = [(meta.getColumnType(i), str(meta.getColumnTypeName(i))) for i in range(1,meta.getColumnCount()+1)]
            self.description = [(str(meta.getColumnLabel(i)),) for i in range(1,meta.getColumnCount()+1)]
        else:
            self.rowcount = self.statement.getUpdateCount()
        return self

    def _value(self, i, code, name):
        r = self.result
        name = name.upper()
        # Read bounded benchmark LOB values directly. Locator.free() performs an
        # Oracle isOpenLob RPC per cell, making independent validation needlessly
        # network-bound. These getters preserve NULL versus an empty LOB.
        if code == 2011 or name == "NCLOB":
            value = r.getNString(i)
            return None if value is None else str(value)
        if code == 2005 or name == "CLOB":
            value = r.getString(i)
            return None if value is None else str(value)
        if code == 2004 or name == "BLOB":
            value = r.getBytes(i)
            return None if value is None else bytes(value)
        obj = r.getObject(i)
        if obj is None:
            return None
        if name in ("BINARY_FLOAT", "BINARY_DOUBLE", "FLOAT", "DOUBLE"):
            return float(r.getDouble(i))
        if code in (2,3):
            return Decimal(str(r.getBigDecimal(i)))
        if code in (-6,5,4,-5):
            return int(r.getLong(i))
        if code in (100,101,6,7,8):
            return float(r.getDouble(i))
        if name in ("TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ") or code in (-101,2014):
            cls = self.connection.jpype.JClass("java.time.OffsetDateTime")
            return datetime.fromisoformat(str(r.getObject(i, cls.class_).toString()))
        if code in (91,93) or name.startswith("TIMESTAMP") or name == "DATE":
            return datetime.fromisoformat(str(r.getTimestamp(i).toLocalDateTime().toString()))
        if code in (-2,-3,-4) or name == "RAW":
            return bytes(r.getBytes(i))
        return str(r.getString(i))

    def fetchone(self):
        if self.result is None or not self.result.next():
            return None
        return tuple(self._value(i,code,name) for i,(code,name) in enumerate(self.types,1))

    def fetchmany(self, size=None):
        result = []
        for _ in range(size or self.arraysize):
            row = self.fetchone()
            if row is None:
                break
            result.append(row)
        return result

    def fetchall(self):
        rows = []
        while True:
            batch = self.fetchmany()
            if not batch:
                return rows
            rows.extend(batch)

    def close(self):
        if self.result is not None:
            self.result.close()
            self.result = None
        if self.statement is not None:
            self.statement.close()
            self.statement = None
        self.description = None
        self.types = []
        self.rowcount = -1

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()
