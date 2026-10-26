"""SKY - DIT performance infrastructure. Run --help for commands.

Oracle -> PG transfers are executed by DIT, never by this collector.
"""
from __future__ import annotations

import argparse
import contextlib
import getpass
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import psutil
import requests

from dit_config import ROOT, get_profile, metadata_connection, open_profile, read_environment, register_uat
from dit_database import (DatabaseSampler, DockerHostSampler, error_code, oracle_inventory, oracle_rows, postgres_inventory)
from dit_metrics import HostSampler, fetch_agent, serve_agent
from dit_report import STAGES, atomic_write, blank_report, now, write_report
from dit_schema import COLUMNS, canonical_row, identifier, oracle_ddl, oracle_seed_sql, postgres_ddl, source_select

DEFAULT_CONFIG = Path(__file__).with_name("config.example.json")
TERMINAL = {"BASARILI", "BASARISIZ", "IPTAL", "SONUCU_BILINMIYOR", "SONUC_BELIRSIZ"}


def read_config(path=DEFAULT_CONFIG):
    config=json.loads(Path(path).read_text(encoding="utf-8-sig"))
    for key in ("sourceOwner","targetSchema","table"):
        identifier(config[key])
    if config["table"]!="SKY_DIT_PERF_50" or config["targetSchema"]!="SKY_DIT_PERF":
        raise ValueError("Yalnız ayrılmış SKY_DIT_PERF.SKY_DIT_PERF_50 test hedefi kullanılabilir.")
    if not config.get("stages") or any(type(v) is not int or v not in STAGES for v in config["stages"]):
        raise ValueError("Hacimler planlanan 100 bin–5 milyon kademelerinden seçilmeli.")
    for key in ("repetitions","sampleIntervalSeconds","databaseIntervalSeconds","timeoutSeconds","seedBatchRows","fetchSize","batchSize"):
        value=config[key]
        if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or value<=0:
            raise ValueError(key+" pozitif sayı olmalı.")
    for key in ("repetitions","seedBatchRows","fetchSize","batchSize"):
        if type(config[key]) is not int:
            raise ValueError(key+" tam sayı olmalı.")
    for key in ("baselineSeconds","cooldownSeconds"):
        if not isinstance(config[key],(int,float)) or not math.isfinite(config[key]) or config[key]<0:
            raise ValueError(key+" negatif olmayan sayı olmalı.")
    if config["batchSize"]>5000:
        raise ValueError("DIT API batchSize üst sınırı 5000.")
    if not isinstance(config.get("processIds",[]),list) or any(type(p) is not int or p<=0 for p in config.get("processIds",[])):
        raise ValueError("processIds pozitif PID listesi olmalı.")
    if not isinstance(config.get("resetTargetBeforeRepetition", False), bool):
        raise ValueError("resetTargetBeforeRepetition must be boolean.")
    output=Path(config["outputDirectory"])
    config["outputDirectory"]=str(output if output.is_absolute() else ROOT/output)
    return config


def require_columns(names):
    if names != [c.name for c in COLUMNS]:
        raise ValueError("Mevcut test tablosu 50 kolon sözleşmesiyle uyuşmuyor; tablo korunarak duruldu.")


def report_status(status):
    return {"BASARILI":"completed","BASARISIZ":"failed","SONUCU_BILINMIYOR":"failed","SONUC_BELIRSIZ":"failed","IPTAL":"cancelled"}.get(status,"running")


def select_new_run(rows,known,project,publication):
    for row in rows:
        if str(row["uuid"]) not in known and str(row["project_uuid"])==project and str(row["publication_uuid"])==publication:
            return str(row["uuid"])
    return None


def validation_result(expected, source_count, source_digest, target_count, target_digest):
    passed=source_count==target_count==expected and source_digest==target_digest
    return {"validationStatus":"passed" if passed else "failed", "rowsRead":source_count,
            "rowsCommitted":target_count, "missingRows":0 if passed else None,
            "duplicateRows":0 if passed else None, "failedRows":0 if passed else None,
            "validationEvidence":{"sourceSha256":source_digest,"targetSha256":target_digest,
                                  "sourceCount":source_count,"targetCount":target_count,"expected":expected}}


def auto_pids(config):
    if config.get("processIds"):
        return config["processIds"]
    found=[]
    for process in psutil.process_iter(["pid","name"]):
        try:
            if "java" in (process.info["name"] or "").lower() and any("tr.com.innova.akis.AkisApplication" in arg for arg in process.cmdline()):
                found.append(process.pid)
        except (psutil.NoSuchProcess, psutil.AccessDenied, OSError):
            continue
    if len(found)>1:
        raise ValueError("Birden fazla backend bulundu; config processIds ile ölçüm kapsamını belirtin.")
    return found


class Context:
    def __init__(self,config):
        self.config=config
        self.output=Path(config["outputDirectory"])
        self.stack=contextlib.ExitStack()
        self.metadata=self.stack.enter_context(metadata_connection())
        self.source=get_profile(self.metadata,config["sourceConnectionCode"])
        self.target=get_profile(self.metadata,config["targetConnectionCode"])
        if self.source.provider!="ORACLE" or self.target.provider!="POSTGRESQL":
            raise ValueError("Kaynak Oracle, hedef PostgreSQL olmalı.")
        self.oracle=self.stack.enter_context(open_profile(self.source,read_only=True))
        self.pg=self.stack.enter_context(open_profile(self.target,read_only=True))
        self.host=HostSampler(auto_pids(config))
        path=self.output/"sky-dit-results.json"
        self.report=json.loads(path.read_text(encoding="utf-8")) if path.exists() else blank_report()
        self.report.setdefault("collection",{"state":"ready","notes":[],"sources":{}})
        self.report.setdefault("diagnostics",[])
        self.notes=[]
        self.report["environment"]={"snapshotAt":now(),"client":self.host.inventory(),
             "oracle":oracle_inventory(self.oracle,self.source,self.notes),
             "postgresql":postgres_inventory(self.pg,self.target,self.notes)}
        self.db=DatabaseSampler(self.oracle,self.pg)
        self.docker=None
        self.agent_urls={role:config.get(role+"AgentUrl","") for role in ("oracle","postgres")}
        self.agent_token=read_environment().get("SKY_DIT_AGENT_TOKEN","")
        self._postgres_host()
        self._agents(inventory=True)
        if not self.host.roots:
            self.notes.append("Backend/worker PID bulunamadı; cihaz toplamı ölçülür, uygulama tüketimi ölçülmez. Backend'i başlatın veya processIds belirtin.")
        else:
            self.notes.append("İzlenen backend/worker kök PID: "+", ".join(map(str,self.host.roots))+"; alt süreçler dahil. Working set toplamında paylaşılan sayfalar birden çok sayılabilir.")
        if not self.agent_urls["oracle"]:
            self.notes.append("Oracle host CPU V$OSSTAT yetkisine bağlıdır. Host RAM için FREE_MEMORY_BYTES veya sunucu ajanı gerekir; SGA/PGA host RAM yerine kullanılmaz.")
        self.notes.append(f"İstemci örnekleme hedefi {config['sampleIntervalSeconds']} sn; DB/global WAL/lock/bekleme örnekleme hedefi {config['databaseIntervalSeconds']} sn. Gerçek tSeconds aralıkları korunur; DB değerleri eksik aralıklara taşınmaz.")
        self.report["collection"]["sources"]={"İstemci":"psutil / toplam cihaz", "Uygulama":"PID ağacı / toplam cihaz kapasitesine normalize CPU", "Oracle":self.source.public(),"PostgreSQL":self.target.public(),"DB metrik kapsamı":"Host/VM ve instance genelidir; yalnız DIT oturumuna ait değildir."}
        self.report["settings"].update(runtime="DIT Java backend; Python ölçüm toplayıcısı",oracleDriver="Uygulama: Oracle JDBC · collector: JDBC/JPype",postgresDriver="Uygulama: PostgreSQL JDBC · collector: psycopg",parallelism=1,fetchSize=config["fetchSize"],batchSize=config["batchSize"],
            sourceQuery=f'SELECT 50 kolon FROM {config["sourceOwner"]}.{config["table"]} WHERE ID BETWEEN 1 AND <hacim> ORDER BY ID',
            dataProfile="50 kolon; NUMBER(38), ondalık/negatif, BINARY_FLOAT/DOUBLE, DATE saat bilgisi, TIMESTAMP(6)/TZ, Türkçe Unicode, CHAR/NCHAR, CLOB/NCLOB, RAW/BLOB, null ve boş LOB.",
            targetSchema=config["targetSchema"]+'.'+config["table"]+'; her tekrar öncesi hedefin uygulama planıyla yenilenmesi gerekir.',
            indexesConstraintsTriggers="Test DDL: ID primary key, NOT NULL; conventional logging. Mevcut şema ayrıca doğrulanır.")
        try:
            commit=subprocess.run(["git","rev-parse","HEAD"],cwd=ROOT,capture_output=True,text=True,timeout=3).stdout.strip()
            dirty=subprocess.run(["git","status","--porcelain"],cwd=ROOT,capture_output=True,text=True,timeout=3).stdout.strip()
            self.report["settings"]["gitCommit"]=commit+(" (çalışma ağacı değişiklikleri var)" if dirty else "")
        except Exception:
            pass
        self.report["method"].update(sampleIntervalSeconds=config["sampleIntervalSeconds"],baselineSeconds=config["baselineSeconds"],cooldownSeconds=config["cooldownSeconds"],
            processScope="Kök PID "+str(list(self.host.roots))+" ve alt süreçler; CPU yüzde toplam mantıksal kapasiteye normalize. Working set toplamı, Windows private commit ayrı seridir.",
            durationScope="Uygulama run baslama_zamani–bitis_zamani; kuyruğa giriş ve içerik doğrulama dahil değil. Zaman yoksa süre null.",
            stageTimingScope="Ayrı okuma/yazma/commit süreleri yalnız DIT bunu ayrı kaydederse doldurulur; birleşik TRANSFER_JDBC süresi ayrıştırılmaz.",
            batchLatencyScope="Batch süreleri uygulamadan gelmedikçe toplayıcı bunları tahmin etmez.",
            validationMethod="Her iki DB'de ID sırasıyla 50 kolonun streaming SHA-256 karşılaştırması. Unicode/LOB/binary/null ve hassas decimal korunur; CHAR sağ boşlukları ve TZ UTC normalize edilir. Hedef toplam sayısı hacimle eşit olmalıdır.",
            clockAlignment="İstemci monotonic zaman ekseni; DB başlangıç/bitiş timestamptz; saat sapması ve geç izleme not edilir. DB/ajan sorgu gecikmesi kayda dahildir.",
            stopConditions=f"Collector timeout {config['timeoutSeconds']} sn; uygulama çalışması otomatik iptal edilmez. Başarısız doğrulamada suite sonraki kademeye geçmez.")
        self.save()

    def _postgres_host(self):
        container=self.config.get("postgresDockerContainer","")
        if not container or self.target.host not in ("127.0.0.1","localhost"):
            return
        try:
            ports=subprocess.run(["docker","port",container,"5432/tcp"],capture_output=True,text=True,timeout=4)
            if ports.returncode or not any(line.strip() in (f"127.0.0.1:{self.target.port}",f"0.0.0.0:{self.target.port}") for line in ports.stdout.splitlines()):
                raise ValueError("Hedef bağlantı Docker port eşlemesiyle doğrulanamadı.")
            self.docker=DockerHostSampler(container)
            self.report["environment"]["postgresql"].update(self.docker.inventory())
            self.notes.append("PostgreSQL CPU/RAM Linux/WSL2 VM toplamıdır (/proc); diğer konteynerler ve işler dahildir. Windows istemcinin toplamından ayrıdır.")
        except Exception as e:
            self.notes.append("PostgreSQL host ölçümü hazırlanamadı: "+error_code(e));self.docker=None

    def _agents(self,inventory=False):
        result={}
        for role,url in self.agent_urls.items():
            if not url:
                continue
            try:
                payload=fetch_agent(url,self.agent_token)
                if time.time()-payload.get("capturedAt",0)>10:
                    raise ValueError("Ajan örneği güncel değil.")
                if inventory:
                    envrole="oracle" if role=="oracle" else "postgresql"
                    inv={k:v for k,v in payload["inventory"].items() if k not in ("version","databaseSettings","instance")}
                    self.report["environment"][envrole].update(inv)
                result[role+"CpuPercent"]=payload["sample"].get("hostCpuPercent")
                result[role+"MemoryUsedMiB"]=payload["sample"].get("hostMemoryUsedMiB")
            except Exception as e:
                note=role+" sunucu ajanı ölçülemedi: "+error_code(e)
                if note not in self.notes:self.notes.append(note)
        return result

    def sample(self,t,phase,db_due):
        sample={"tSeconds":round(t,6),"phase":phase,**self.host.sample()}
        if db_due:
            sample.update(self.db.sample())
            if self.docker:
                try:sample.update(self.docker.sample())
                except Exception as e:
                    note="PostgreSQL VM metriği eksik: "+error_code(e)
                    if note not in self.notes:self.notes.append(note)
            sample.update(self._agents())
        return sample

    def save(self):
        collection=self.report["collection"]
        collection["notes"]=list(dict.fromkeys([*self.notes,*self.db.notes,*self.host.notes]))
        return write_report(self.output,self.report)

    def close(self):self.stack.close()
    def __enter__(self):return self
    def __exit__(self,*_args):self.close()


def prepare(ctx):
    c=ctx.config;owner=c["sourceOwner"].upper();table=c["table"]
    actual=oracle_rows(ctx.oracle,"select sys_context('USERENV','SESSION_USER') from dual")[0][0]
    if ctx.source.code!="SKY_UAT" or ctx.source.service!="TTBPSKY" or actual!=owner:
        raise ValueError("Hazırlama yalnız kullanıcı tarafından belirtilen SKY_UAT / TTBPSKY / kendi hesap şeması için açıktır.")
    existing=oracle_rows(ctx.oracle,"select column_name from all_tab_columns where owner=:owner and table_name=:table_name order by column_id",{"owner":owner,"table_name":table})
    if not existing:
        with open_profile(ctx.source) as conn:
            with conn.cursor() as cur:cur.execute(oracle_ddl(owner,table))
    existing=oracle_rows(ctx.oracle,"select column_name from all_tab_columns where owner=:owner and table_name=:table_name order by column_id",{"owner":owner,"table_name":table})
    require_columns([r[0] for r in existing])
    with open_profile(ctx.target) as pg:
        schema=c["targetSchema"]
        pg.execute("CREATE SCHEMA IF NOT EXISTS "+identifier(schema))
        existing=pg.execute("select column_name from information_schema.columns where table_schema=%s and table_name=%s order by ordinal_position",(schema,table)).fetchall()
        if not existing:pg.execute(postgres_ddl(schema,table))
        existing=pg.execute("select column_name from information_schema.columns where table_schema=%s and table_name=%s order by ordinal_position",(schema,table)).fetchall()
        require_columns([r[0] for r in existing])
    # The new PG test schema is discoverable in the application; no existing schema binding is replaced.
    ctx.metadata.execute("""insert into akis.fiziksel_sema(baglanti_id,kod,ad,saglayici_turu,sema_adi,calisma_sema_adi)
         select b.id,'SKY_DIT_PERF','SKY DIT Performans','POSTGRESQL',%s,%s from akis.baglanti b where b.kod=%s
         and not exists(select 1 from akis.fiziksel_sema f where f.baglanti_id=b.id and f.sema_adi=%s)""",
         (c["targetSchema"],c["targetSchema"],ctx.target.code,c["targetSchema"]))
    sql_dir=ctx.output/"sql"
    atomic_write(sql_dir/"oracle-create.sql",oracle_ddl(owner,table)+";\n")
    atomic_write(sql_dir/"postgres-create.sql",'CREATE SCHEMA IF NOT EXISTS '+identifier(c["targetSchema"])+";\n"+postgres_ddl(c["targetSchema"],table)+";\n")
    for stage in STAGES:atomic_write(sql_dir/f"select-{stage}.sql",source_select(owner,table,stage)+";\n")
    src=oracle_rows(ctx.oracle,'select count(*) from '+identifier(owner)+'.'+identifier(table))[0][0]
    tgt=ctx.pg.execute('select count(*) from '+identifier(c["targetSchema"])+'.'+identifier(table)).fetchone()[0]
    ctx.notes.append(f"50 kolon doğrulandı: Oracle {owner}.{table} ({src} satır); PostgreSQL {c['targetSchema']}.{table} ({tgt} satır). Hazırlık mevcut satırları silmez.")
    ctx.report["collection"]["state"]="ready";ctx.save()
    print(f"PREPARED sourceColumns=50 targetColumns=50 sourceRows={src} targetRows={tgt}",flush=True)


def seed(ctx,rows):
    if type(rows) is not int or not 1<=rows<=5000000:raise ValueError("Veri üretimi 1–5.000.000 kayıt aralığında olmalı.")
    prepare(ctx)
    table=identifier(ctx.config["sourceOwner"])+'.'+identifier(ctx.config["table"])
    count,min_id,max_id=oracle_rows(ctx.oracle,'select count(*),min("ID"),max("ID") from '+table)[0]
    count=int(count);max_id=int(max_id or 0)
    if count and (int(min_id)!=1 or count!=max_id):raise ValueError("Kaynak ID aralığı yoğun 1..N değil; tabloya veri eklenmedi.")
    if max_id>rows:raise ValueError("Kaynak zaten istenenden büyük; veri silinmedi. Kademeli SELECT aralığını kullanın.")
    with open_profile(ctx.source) as connection:
        with connection.cursor() as cursor:
            while max_id<rows:
                amount=min(ctx.config["seedBatchRows"],rows-max_id)
                cursor.execute(oracle_seed_sql(ctx.config["sourceOwner"],ctx.config["table"]),{"start_id":max_id+1,"row_count":amount})
                if cursor.rowcount!=amount:raise ValueError("Üretim satır sayısı beklenenden farklı; commit yapılmadı.")
                connection.commit();max_id+=amount
                print(f"SEEDED {max_id}/{rows}",flush=True)
    ctx.notes.append(f"Kaynak sentetik veri üretimi tamamlandı: {rows} kayıt. Bu işlem aktarım performans testi değildir.");ctx.save()


def diagnostic(ctx,seconds):
    if not math.isfinite(seconds) or not 1<=seconds<=300:raise ValueError("Ölçüm provası 1–300 saniye aralığında olmalı.")
    d={"id":"preflight-"+datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"),"startedAt":now(),"durationSeconds":seconds,"samples":[],"events":[{"tSeconds":0,"label":"Yalnız kaynak ölçüm provası; DIT aktarımı başlatılmadı."}]}
    ctx.report["diagnostics"].append(d);ctx.report["collection"]["state"]="collecting"
    ctx.host.sample()  # Prime interval counters after inventory, immediately before the measurement window.
    start=time.monotonic();next_db=0;next_tick=start+ctx.config["sampleIntervalSeconds"]
    while True:
        time.sleep(max(0,next_tick-time.monotonic()))
        elapsed=time.monotonic()-start
        due=elapsed>=next_db
        d["samples"].append(ctx.sample(elapsed,"baseline",due))
        if due:next_db=elapsed+ctx.config["databaseIntervalSeconds"]
        d["durationSeconds"]=time.monotonic()-start;ctx.save()
        if elapsed>=seconds:break
        next_tick+=ctx.config["sampleIntervalSeconds"]
        if next_tick<time.monotonic():next_tick=time.monotonic()+ctx.config["sampleIntervalSeconds"]
    ctx.report["collection"]["state"]="ready";ctx.save()
    print(f"SAMPLER_OK samples={len(d['samples'])}; no transfer started",flush=True)


def get_run(ctx,run_id):
    row=ctx.metadata.execute("""select r.id,r.uuid,d.durum,d.baslama_zamani,d.bitis_zamani,d.isleyici_referansi,
                y.uuid as publication_uuid,p.uuid as project_uuid,o.kod as environment_code
         from akis.calistirma r join akis.calistirma_durumu d on d.calistirma_id=r.id
         join akis.is_talebi j on j.id=r.is_talebi_id join akis.yayin y on y.id=j.yayin_id
         join akis.proje p on p.id=r.proje_id join akis.ortam o on o.id=y.ortam_id where r.uuid=%s""",(uuid.UUID(run_id),)).fetchone()
    if not row:raise ValueError("DIT çalıştırması bulunamadı.")
    return row


def digest_cursor(cursor):
    digest=hashlib.sha256();count=0
    while True:
        rows=cursor.fetchmany(1000)
        if not rows:return count,digest.hexdigest()
        for row in rows:digest.update(canonical_row(row));count+=1


def validate_content(ctx,rows):
    with ctx.oracle.cursor() as cursor:
        cursor.arraysize=ctx.config["fetchSize"]
        cursor.execute(source_select(ctx.config["sourceOwner"],ctx.config["table"],rows))
        source_count,source_hash=digest_cursor(cursor)
    # A server-side PG cursor prevents materializing millions of rows in collector RAM.
    with open_profile(ctx.target,read_only=True) as pg:
        pg.autocommit=False
        with pg.cursor(name="sky_dit_validation") as cursor:
            cursor.execute(source_select(ctx.config["targetSchema"],ctx.config["table"],rows))
            target_count,target_hash=digest_cursor(cursor)
        total=pg.execute('select count(*) from '+identifier(ctx.config["targetSchema"])+'.'+identifier(ctx.config["table"])).fetchone()[0]
    result=validation_result(rows,source_count,source_hash,target_count,target_hash)
    if total!=rows:result.update(validationStatus="failed",missingRows=None,duplicateRows=None,failedRows=None)
    result["validationEvidence"]["targetTotalRows"]=total
    return result


def reset_target_for_repetition(ctx, rows):
    """Reset only the reserved performance target between independent repeats."""
    if not ctx.config.get("resetTargetBeforeRepetition", False):
        return
    schema = identifier(ctx.config["targetSchema"])
    table = identifier(ctx.config["table"])
    with open_profile(ctx.target) as pg:
        before = pg.execute(f"select count(*) from {schema}.{table}").fetchone()[0]
        pg.execute(f"delete from {schema}.{table}")
        after = pg.execute(f"select count(*) from {schema}.{table}").fetchone()[0]
    if after != 0:
        raise RuntimeError("Reserved performance target could not be reset before repetition.")
    ctx.notes.append(f"Target reset before {rows}-row repetition: {before} -> 0 rows.")


def monitor(ctx,run_id,rows,repetition=1,purpose="measurement",baseline=None):
    if rows not in STAGES:raise ValueError("Planlanan hacim kademesi seçilmeli.")
    info=get_run(ctx,run_id)
    if info["environment_code"]!="UAT":raise ValueError("Yalnız UAT ortamındaki uygulama çalıştırmaları ölçülebilir.")
    if str(info["project_uuid"])!=ctx.config["projectUuid"]:raise ValueError("Çalıştırma config proje kimliğiyle uyuşmuyor.")
    if not ctx.host.roots:raise ValueError("Uygulama ölçümü için çalışan backend PID'si gerekir.")
    if any(r["id"]==run_id for r in ctx.report["runs"]):raise ValueError("Bu run UUID raporda zaten var; üzerine yazılmadı.")
    if info["durum"] in TERMINAL:raise ValueError("Bitmiş çalıştırmanın kaynak ölçümleri sonradan üretilemez; yeni run izleyin.")
    run={**ctx.report["runTemplate"],"id":run_id,"targetRows":rows,"repetition":repetition,"purpose":purpose,"status":"running","startedAt":info["baslama_zamani"].isoformat() if info["baslama_zamani"] else now(),"samples":[],"events":[],"notes":"DIT run UUID üzerinden izlenir; collector aktarım yapmaz."}
    ctx.report["runs"].append(run);ctx.report["collection"]["state"]="collecting";ctx.report["report"]["testedAt"]=now()
    origin=time.monotonic();next_tick=origin;next_db=0;last_event=0;last_status="";finished_seen=None
    if baseline:
        for sample in baseline:
            run["samples"].append({**sample,"tSeconds":sample["tSeconds"]-baseline[-1]["tSeconds"]-ctx.config["sampleIntervalSeconds"]})
    try:
        while True:
            time.sleep(max(0,next_tick-time.monotonic()))
            elapsed=time.monotonic()-origin
            info=get_run(ctx,run_id)
            started=info["baslama_zamani"]
            t=(datetime.now(timezone.utc)-started).total_seconds() if started else elapsed
            # Align polling to DB run origin. Never claim historical CPU samples before collector attachment.
            if run["samples"] and t<=run["samples"][-1]["tSeconds"]:t=run["samples"][-1]["tSeconds"]+.000001
            done=info["durum"] in TERMINAL
            phase="cooldown" if done else "transfer"
            run["samples"].append(ctx.sample(t,phase,elapsed>=next_db))
            if elapsed>=next_db:next_db=elapsed+ctx.config["databaseIntervalSeconds"]
            if started:run["startedAt"]=started.isoformat()
            if info["durum"]!=last_status:
                run["events"].append({"tSeconds":t,"label":"DIT durumu: "+info["durum"]});last_status=info["durum"]
            events=ctx.metadata.execute("select olay_no,tur,olay_zamani from akis.calistirma_olayi where calistirma_id=%s and olay_no>%s order by olay_no limit 200",(info["id"],last_event)).fetchall()
            for e in events:
                et=(e["olay_zamani"]-started).total_seconds() if started else t
                run["events"].append({"tSeconds":et,"label":str(e["tur"])});last_event=e["olay_no"]
            if done:
                run["status"]=report_status(info["durum"])
                if started and info["bitis_zamani"]:run["durationSeconds"]=(info["bitis_zamani"]-started).total_seconds()
                if finished_seen is None:finished_seen=elapsed
                if elapsed-finished_seen>=ctx.config["cooldownSeconds"]:break
            ctx.save()
            if elapsed>=ctx.config["timeoutSeconds"]:
                raise TimeoutError("Collector zaman aşımı; DIT çalışması otomatik iptal edilmedi.")
            next_tick=time.monotonic()+ctx.config["sampleIntervalSeconds"]
        if run["status"]=="completed":
            run.update(validate_content(ctx,rows))
        else:
            run["validationStatus"]="pending"
        ctx.report["collection"]["state"]="complete"
    except BaseException as e:
        run["notes"]+=" Ölçüm kesildi: "+error_code(e)+"; uygulama durumu son gözleme göre saklandı."
        ctx.report["collection"]["state"]="blocked"
        ctx.save();raise
    ctx.save()
    return run


def watch_ui(ctx,publication,rows,repetition):
    publication=str(uuid.UUID(publication))
    view=ctx.metadata.execute("select y.uuid,o.kod from akis.yayin y join akis.ortam o on o.id=y.ortam_id join akis.proje p on p.id=y.proje_id where y.uuid=%s and p.uuid=%s",(uuid.UUID(publication),uuid.UUID(ctx.config["projectUuid"]))).fetchone()
    if not view or view["kod"]!="UAT":raise ValueError("UI izleme yalnız belirtilen projenin UAT yayını için açılır.")
    def candidates():
        return ctx.metadata.execute("""select r.uuid,y.uuid as publication_uuid,p.uuid as project_uuid from akis.calistirma r
            join akis.is_talebi j on j.id=r.is_talebi_id join akis.yayin y on y.id=j.yayin_id
            join akis.proje p on p.id=r.proje_id where y.uuid=%s order by r.id desc""",(uuid.UUID(publication),)).fetchall()
    known={str(r["uuid"]) for r in candidates()}
    start=time.monotonic();baseline=[];next_db=0;ctx.host.sample()
    print("ARMED: arayüzde bu UAT yayınının Run düğmesine basılmasını bekliyor.",flush=True)
    while time.monotonic()-start<ctx.config["timeoutSeconds"]:
        run_id=select_new_run(candidates(),known,ctx.config["projectUuid"],publication)
        if run_id:
            print("ATTACHED_RUN="+run_id,flush=True)
            return monitor(ctx,run_id,rows,repetition,baseline=baseline)
        elapsed=time.monotonic()-start
        baseline.append(ctx.sample(elapsed,"baseline",elapsed>=next_db))
        baseline=baseline[-max(1,int(ctx.config["baselineSeconds"]/ctx.config["sampleIntervalSeconds"])):]
        if elapsed>=next_db:next_db=elapsed+ctx.config["databaseIntervalSeconds"]
        time.sleep(ctx.config["sampleIntervalSeconds"])
    raise TimeoutError("Arayüzden yeni çalıştırma gelmedi; uygulamada işlem başlatılmadı.")


def api_session(base):
    from urllib.parse import urlsplit
    p=urlsplit(base)
    if p.scheme!="https" and not (p.scheme=="http" and p.hostname in ("127.0.0.1","localhost")):
        raise ValueError("Uygulama API HTTPS veya yerel adres olmalı.")
    env=read_environment();user=env.get("SKY_DIT_API_USER") or input("DIT kullanıcı kodu: ")
    password=env.get("SKY_DIT_API_PASSWORD") or getpass.getpass("DIT parolası: ")
    session=requests.Session()
    csrf=session.get(base+"/api/v1/auth/csrf",timeout=10);csrf.raise_for_status();token=csrf.json()
    result=session.post(base+"/api/v1/auth/login",json={"kullaniciKodu":user,"parola":password},headers={token["headerName"]:token["token"]},timeout=10)
    result.raise_for_status()
    token=session.get(base+"/api/v1/auth/csrf",timeout=10).json()
    session.headers[token["headerName"]]=token["token"]
    return session


def suite(ctx):
    c=ctx.config
    if not c.get("publications") or any(str(stage) not in c["publications"] for stage in c["stages"]):
        raise ValueError("Her hacim için UAT'ta yayınlanmış gerçek DIT publication UUID'si config.publications içinde gerekir. Hazır SELECT SQL dosyalarını kullanın.")
    if not ctx.host.roots:raise ValueError("DIT backend çalışmıyor veya PID kapsamı bulunamadı.")
    base=c["backendUrl"].rstrip("/")
    with api_session(base) as session:
        for stage in c["stages"]:
            publication=str(uuid.UUID(c["publications"][str(stage)]))
            # Verify normal API publication environment before creating an application run.
            view=session.get(base+f"/api/v1/projects/{c['projectUuid']}/publications/{publication}",timeout=15)
            view.raise_for_status()
            if view.json().get("environmentCode")!="UAT":raise ValueError("Suite yalnız UAT yayınlarını başlatabilir.")
            for repetition in range(c.get("startRepetition",1),c["repetitions"]+1):
                reset_target_for_repetition(ctx, stage)
                baseline=[];start=time.monotonic();next_db=0
                while time.monotonic()-start<c["baselineSeconds"]:
                    time.sleep(c["sampleIntervalSeconds"]);elapsed=time.monotonic()-start
                    baseline.append(ctx.sample(elapsed,"baseline",elapsed>=next_db))
                    if elapsed>=next_db:next_db=elapsed+c["databaseIntervalSeconds"]
                # A transfer plus full-content validation can outlive the login session.
                # Authenticate for each new run rather than reuse an idle session.
                with api_session(base) as run_session:
                    response=run_session.post(base+f"/api/v1/projects/{c['projectUuid']}/runs",json={"publicationUuid":publication,"batchRows":c["batchSize"]},headers={"Idempotency-Key":"sky-dit-perf-"+str(uuid.uuid4())},timeout=30)
                response.raise_for_status();run_id=response.json()["runUuid"]
                result=monitor(ctx,run_id,stage,repetition,baseline=baseline)
                if result["status"]!="completed" or result["validationStatus"]!="passed":
                    raise ValueError("Çalıştırma veya 50 kolon içerik doğrulaması başarısız; suite durduruldu.")


def main():
    parser=argparse.ArgumentParser(description="SKY - DIT · Data Integration Tool performans altyapısı")
    parser.add_argument("command",choices=["inventory","prepare","seed","sample","monitor","watch","suite","register-uat","agent"])
    parser.add_argument("--config",type=Path,default=DEFAULT_CONFIG)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--rows",type=int)
    parser.add_argument("--seconds",type=float,default=10)
    parser.add_argument("--run-uuid")
    parser.add_argument("--publication-uuid")
    parser.add_argument("--repetition",type=int,default=1)
    parser.add_argument("--pid",type=int,action="append")
    parser.add_argument("--host");parser.add_argument("--port",type=int,default=1521);parser.add_argument("--service");parser.add_argument("--user")
    parser.add_argument("--agent-port",type=int,default=8765)
    args=parser.parse_args()
    if args.command=="agent":return serve_agent(args.agent_port,read_environment().get("SKY_DIT_AGENT_TOKEN"))
    if args.command=="register-uat":
        if not all([args.host,args.service,args.user]):parser.error("register-uat: --host --service --user gerekir")
        password=getpass.getpass("UAT bağlantı parolası: ")
        from dit_config import Profile
        p=Profile("SKY_UAT","ORACLE",args.host,args.port,args.user,password,service=args.service)
        with open_profile(p,read_only=True):pass
        with metadata_connection() as metadata:register_uat(metadata,host=args.host,port=args.port,service=args.service,username=args.user,password=password)
        print("SKY_UAT bağlantısı doğrulandı ve şifreli kaydedildi.");return
    config=read_config(args.config)
    if args.output:config["outputDirectory"]=str(args.output.resolve())
    if args.pid:config["processIds"]=args.pid
    with Context(config) as ctx:
        if args.command=="prepare":prepare(ctx)
        elif args.command=="seed":
            if args.rows is None:parser.error("seed: --rows gerekir")
            seed(ctx,args.rows)
        elif args.command=="sample":diagnostic(ctx,args.seconds)
        elif args.command=="monitor":
            if not args.run_uuid or args.rows is None:parser.error("monitor: --run-uuid ve --rows gerekir")
            monitor(ctx,args.run_uuid,args.rows,args.repetition)
        elif args.command=="suite":suite(ctx)
        elif args.command=="watch":
            if not args.publication_uuid or args.rows is None:parser.error("watch: --publication-uuid ve --rows gerekir")
            watch_ui(ctx,args.publication_uuid,args.rows,args.repetition)
        ctx.save()
        print("REPORT="+str(ctx.output/"sky-dit-report.html"),flush=True)


if __name__=="__main__":
    try:main()
    except KeyboardInterrupt:
        print("Collector durduruldu; uygulama çalışması otomatik iptal edilmedi.",file=sys.stderr);sys.exit(130)
    except Exception as error:
        # Avoid JDBC/HTTP exception strings, SQL, URLs, or credential-bearing tracebacks in CLI output.
        safe=str(error) if isinstance(error,(ValueError,TimeoutError)) else error_code(error)
        print("HATA: "+safe,file=sys.stderr);sys.exit(1)
