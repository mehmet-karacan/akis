import importlib
import json
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class RunnerTests(unittest.TestCase):
    def module(self):
        try:
            return importlib.import_module("sky_dit_perf")
        except ImportError:
            self.fail("Missing implementation: sky_dit_perf")

    def test_configuration_rejects_wrong_stage_and_unscoped_target(self):
        m=self.module()
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/"config.json"
            d=json.loads((Path(__file__).parents[1]/"config.example.json").read_text())
            d["table"]="CUSTOMER";path.write_text(json.dumps(d))
            with self.assertRaises(ValueError):m.read_config(path)
            d["table"]="SKY_DIT_PERF_50";d["stages"]=[100];path.write_text(json.dumps(d))
            with self.assertRaises(ValueError):m.read_config(path)

    def test_finished_run_is_only_completed_when_dit_reports_success(self):
        m=self.module()
        self.assertEqual(m.report_status("BASARILI"),"completed")
        self.assertEqual(m.report_status("BASARISIZ"),"failed")
        self.assertEqual(m.report_status("IPTAL"),"cancelled")
        self.assertEqual(m.report_status("SONUCU_BILINMIYOR"),"failed")
        self.assertEqual(m.report_status("SONUC_BELIRSIZ"),"failed")
        self.assertIn("SONUC_BELIRSIZ",m.TERMINAL)
        self.assertEqual(m.report_status("CALISIYOR"),"running")

    def test_digest_mismatch_does_not_pass_even_when_counts_match(self):
        m=self.module()
        result=m.validation_result(100000,100000,"source-hash",100000,"different-target-hash")
        self.assertEqual(result["validationStatus"],"failed")
        self.assertIsNone(result["missingRows"])
        result=m.validation_result(100000,100000,"same",100000,"same")
        self.assertEqual(result["validationStatus"],"passed")
        self.assertEqual(result["duplicateRows"],0)
        self.assertEqual(result["failedRows"],0)

    def test_table_contract_refuses_reordered_or_missing_columns(self):
        m=self.module()
        from dit_schema import COLUMNS
        names=[c.name for c in COLUMNS]
        m.require_columns(names)
        with self.assertRaises(ValueError):m.require_columns(names[:-1])
        with self.assertRaises(ValueError):m.require_columns(names[::-1])

    def test_ui_watcher_only_attaches_to_new_run_of_the_requested_publication(self):
        m=self.module()
        rows=[{"uuid":"old","publication_uuid":"pub","project_uuid":"project"},
              {"uuid":"wrong-publication","publication_uuid":"other","project_uuid":"project"},
              {"uuid":"right","publication_uuid":"pub","project_uuid":"project"}]
        self.assertEqual(m.select_new_run(rows,{"old"},"project","pub"),"right")
        self.assertIsNone(m.select_new_run(rows,{"old","right"},"project","pub"))

    def test_suite_renews_authentication_after_long_measurements(self):
        m=self.module()
        sessions=[]
        created=[]
        class Session:
            expired=False
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def get(self,*args,**kwargs):
                return SimpleNamespace(raise_for_status=lambda:None,json=lambda:{'environmentCode':'UAT'})
            def post(self,*args,**kwargs):
                if self.expired: raise RuntimeError('401: session expired during measurement')
                created.append(kwargs['json']['publicationUuid'])
                return SimpleNamespace(raise_for_status=lambda:None,json=lambda:{'runUuid':str(len(created))})
        def authenticate(base):
            s=Session();sessions.append(s);return s
        def monitor(*args,**kwargs):
            for s in sessions:s.expired=True
            return {'status':'completed','validationStatus':'passed'}
        config={'publications':{'100000':'00000000-0000-0000-0000-000000000001'},
                'stages':[100000],'repetitions':2,'backendUrl':'http://127.0.0.1:8080',
                'projectUuid':'project','baselineSeconds':0,'batchSize':1000}
        ctx=SimpleNamespace(config=config,host=SimpleNamespace(roots=[1]))
        with patch.object(m,'api_session',side_effect=authenticate), \
             patch.object(m,'reset_target_for_repetition'),patch.object(m,'monitor',side_effect=monitor):
            m.suite(ctx)
        self.assertEqual(len(created),2)

    def test_suite_resume_starts_at_requested_repetition(self):
        m=self.module()
        response=SimpleNamespace(raise_for_status=lambda:None,json=lambda:{'environmentCode':'UAT','runUuid':'run'})
        from unittest.mock import MagicMock
        session=MagicMock()
        session.__enter__.return_value=session
        session.get.return_value=response;session.post.return_value=response
        ctx=SimpleNamespace(host=SimpleNamespace(roots=[1]),config={
            'publications':{'500000':'00000000-0000-0000-0000-000000000001'},
            'stages':[500000],'repetitions':3,'startRepetition':2,
            'backendUrl':'http://127.0.0.1:8080','projectUuid':'project',
            'baselineSeconds':0,'batchSize':1000})
        completed=[]
        def monitor(ctx,run,rows,repetition,**kwargs):
            completed.append(repetition)
            return {'status':'completed','validationStatus':'passed'}
        with patch.object(m,'api_session',return_value=session), \
             patch.object(m,'reset_target_for_repetition'),patch.object(m,'monitor',side_effect=monitor):
            m.suite(ctx)
        self.assertEqual(completed,[2,3])


if __name__=="__main__":unittest.main()
