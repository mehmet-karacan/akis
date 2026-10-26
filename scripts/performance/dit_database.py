"""Read-only database inventory and interval metrics; unavailable is never zero."""
from __future__ import annotations

import json
import re
import subprocess
import time

from dit_config import ROOT
from dit_metrics import counter_cpu, MIB


def error_code(error):
    match = re.search(r"(?:ORA|DPY|DPI)-\d+", str(error))
    return match.group() if match else getattr(error, "sqlstate", None) or type(error).__name__


def oracle_rows(connection, sql, params=None):
    with connection.cursor() as cursor:
        cursor.execute(sql, params)
        return cursor.fetchall()


def oracle_inventory(connection, profile, notes):
    env = {"hostLabel":profile.host, "roleLocation":"Uzak Oracle DB sunucusu", "version":connection.version.splitlines()[0],
           "instance":profile.service or profile.sid, "network":f"{profile.host}:{profile.port}",
           "databaseSettings":"", "limits":"Sunucu OS erişimi ve dinamik görünüm yetkileri ile sınırlı."}
    try:
        row = oracle_rows(connection,"select sys_context('USERENV','SERVER_HOST'),sys_context('USERENV','INSTANCE_NAME'),sys_context('USERENV','DB_NAME') from dual")[0]
        env.update(hostLabel=row[0] or profile.host, instance=" / ".join(str(v) for v in row[1:] if v))
    except Exception as e:
        notes.append("Oracle sunucu kimliği: "+error_code(e))
    try:
        stats = dict(oracle_rows(connection,"select stat_name,value from v$osstat where stat_name in ('NUM_CPUS','NUM_CPU_CORES','PHYSICAL_MEMORY_BYTES')"))
        env.update(logicalCores=int(stats["NUM_CPUS"]) if "NUM_CPUS" in stats else "",
                   physicalCores=int(stats["NUM_CPU_CORES"]) if "NUM_CPU_CORES" in stats else "",
                   memoryGiB=round(float(stats["PHYSICAL_MEMORY_BYTES"])/1024**3,2) if "PHYSICAL_MEMORY_BYTES" in stats else "")
    except Exception as e:
        notes.append("Oracle OS envanteri ölçülemedi (V$OSSTAT): "+error_code(e))
    settings = {}
    for title,sql in [("parameters","select name,value from v$parameter where name in ('sga_target','pga_aggregate_target','db_block_size','processes','sessions','open_cursors')"),
                      ("charset","select parameter,value from nls_database_parameters where parameter in ('NLS_CHARACTERSET','NLS_NCHAR_CHARACTERSET')")]:
        try:
            settings[title] = dict(oracle_rows(connection,sql))
        except Exception as e:
            notes.append("Oracle "+title+" bilgisi ölçülemedi: "+error_code(e))
    env["databaseSettings"] = json.dumps(settings,ensure_ascii=False,default=str)
    return env


def postgres_inventory(connection, profile, notes):
    version,database,server,port = connection.execute("select version(),current_database(),inet_server_addr()::text,inet_server_port()").fetchone()
    settings = dict(connection.execute("""select name,setting||coalesce(' '||unit,'') from pg_settings where name in
                       ('server_version','server_encoding','shared_buffers','work_mem','maintenance_work_mem','max_connections',
                        'max_wal_size','checkpoint_timeout','wal_level','synchronous_commit','fsync','full_page_writes')""").fetchall())
    return {"hostLabel":server or profile.host, "roleLocation":"PostgreSQL DB sunucusu; host bilgisi için ajan veya eşleşen Docker ölçümü",
            "version":version, "instance":database, "network":f"{profile.host}:{port}",
            "databaseSettings":json.dumps(settings,ensure_ascii=False),
            "limits":"SQL tek başına host CPU/RAM vermez; süreç/konteyner/VM kapsamı ayrı tutulur."}


class DatabaseSampler:
    def __init__(self, oracle, postgres):
        self.oracle, self.postgres = oracle, postgres
        self.previous_os = self.previous_wal = self.previous_wait = None
        self.previous_time = time.monotonic()
        self.disabled = set()
        self.notes = set()

    def sample(self):
        result = {}
        now = time.monotonic()
        if "oracle-os" not in self.disabled:
            try:
                current = {k:float(v) for k,v in oracle_rows(self.oracle,"select stat_name,value from v$osstat where stat_name in ('BUSY_TIME','IDLE_TIME','PHYSICAL_MEMORY_BYTES','FREE_MEMORY_BYTES')")}
                result["oracleCpuPercent"] = counter_cpu(self.previous_os,current)
                if "PHYSICAL_MEMORY_BYTES" in current and "FREE_MEMORY_BYTES" in current:
                    result["oracleMemoryUsedMiB"]=(current["PHYSICAL_MEMORY_BYTES"]-current["FREE_MEMORY_BYTES"])/MIB
                self.previous_os = current
            except Exception as e:
                self.disabled.add("oracle-os"); self.notes.add("Oracle host CPU/RAM ölçümü yok (V$OSSTAT): "+error_code(e))
        if "oracle-wait" not in self.disabled:
            try:
                wait=float(oracle_rows(self.oracle,"select sum(time_waited_micro) from v$system_event where wait_class <> 'Idle'")[0][0] or 0)
                result["oracleWaitMs"]=(wait-self.previous_wait)/1000 if self.previous_wait is not None and wait>=self.previous_wait else None
                self.previous_wait=wait
            except Exception as e:
                self.disabled.add("oracle-wait");self.notes.add("Oracle sistem bekleme örneği yok: "+error_code(e))
        if "postgres-wal" not in self.disabled:
            try:
                row=self.postgres.execute("select wal_bytes::numeric,stats_reset from pg_stat_wal").fetchone()
                value=float(row[0]);reset=str(row[1]);elapsed=now-self.previous_time
                if self.previous_wal and self.previous_wal[1]==reset and value>=self.previous_wal[0] and elapsed>0:
                    result["postgresWalBytesPerSecond"]=(value-self.previous_wal[0])/elapsed
                self.previous_wal=(value,reset)
            except Exception as e:
                self.disabled.add("postgres-wal");self.notes.add("PostgreSQL WAL örneği yok: "+error_code(e))
        try:
            result["postgresLockWaitCount"]=self.postgres.execute("select count(*) from pg_locks where not granted").fetchone()[0]
        except Exception as e:
            self.notes.add("PostgreSQL lock örneği yok: "+error_code(e))
        self.previous_time=now
        return result


class DockerHostSampler:
    """Linux VM /proc as seen by the matched DB container. This is NOT container CPU/RAM."""
    def __init__(self, container):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", container):
            raise ValueError("Docker container adı geçersiz.")
        self.container=container
        self.previous=None

    def read(self):
        # Constant shell script, no interpolated user arguments. Read-only Linux pseudo-files.
        script="uname -sr; cat /proc/meminfo; cat /proc/stat; cat /proc/cpuinfo; cat /sys/fs/cgroup/memory.max; cat /sys/fs/cgroup/cpu.max"
        result=subprocess.run(["docker","exec",self.container,"sh","-c",script],capture_output=True,text=True,timeout=4)
        if result.returncode:
            raise ValueError("Eşleşen PostgreSQL konteynerinin Linux host istatistikleri okunamadı.")
        return result.stdout

    def inventory(self):
        raw=self.read()
        memory=dict((k,int(v)) for k,v in re.findall(r"^(MemTotal|MemAvailable):\s+(\d+) kB",raw,re.M))
        cpus=re.findall(r"^cpu\d+\s",raw,re.M)
        model=re.search(r"^model name\s*:\s*(.+)$",raw,re.M)
        return {"hostLabel":self.container+" / Linux host VM", "roleLocation":"Docker üzerinden /proc: DB'nin bulunduğu Linux/WSL2 VM toplamı; diğer konteynerler dahil",
                "os":raw.splitlines()[0], "logicalCores":len(cpus), "cpu":model.group(1) if model else "",
                "memoryGiB":round(memory.get("MemTotal",0)/1024**2,2),
                "limits":"/proc host/VM kapsamı; cgroup tahsisi değildir. PostgreSQL süreç tüketimi diye yorumlanmaz."}

    def sample(self):
        raw=self.read()
        memory=dict((k,int(v)) for k,v in re.findall(r"^(MemTotal|MemAvailable):\s+(\d+) kB",raw,re.M))
        match=re.search(r"^cpu\s+(.+)$",raw,re.M)
        ticks=[int(x) for x in match.group(1).split()][:8]
        idle=ticks[3]+ticks[4]
        current={"BUSY_TIME":sum(ticks)-idle,"IDLE_TIME":idle}
        cpu=counter_cpu(self.previous,current);self.previous=current
        return {"postgresCpuPercent":cpu,"postgresMemoryUsedMiB":(memory["MemTotal"]-memory["MemAvailable"])/1024}
