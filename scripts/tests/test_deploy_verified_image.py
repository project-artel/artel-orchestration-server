"""Exercise the real deployment shell against a stateful Docker command boundary."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'deploy-verified-image.sh'
IMAGE = 'sha256:' + 'a' * 64
NAME = 'artel-orchestration-server-stage'
DOCKER = r'''#!/usr/bin/env python3
import json, os, pathlib, signal, sys
p=pathlib.Path(os.environ['MOCK_STATE']); s=json.loads(p.read_text()); a=sys.argv[1:]
s['calls'].append(a); rc=0; out=''
mode=os.environ.get('MOCK_MODE','ok')
if a[:2]==['image','inspect']:
    out = ('wrong' if mode=='wrong_label' else os.environ['SOURCE_JOB']) if 'job' in a[3] else (os.environ['SOURCE_BUILD'] if 'build' in a[3] else os.environ['SOURCE_COMMIT'])
elif a[0]=='inspect':
    c=s['containers'].get(a[-1])
    if c is None: rc=1
    elif '-f' in a:
        out=str(c.get('restarts',0)) if 'RestartCount' in a[2] else str(c['running']).lower()
elif a[0]=='rename':
    if mode=='rename_fail' and a[2].endswith('-rollback'):rc=1
    elif a[1] not in s['containers'] or a[2] in s['containers']:rc=1
    else:s['containers'][a[2]]=s['containers'].pop(a[1])
elif a[0] in ['stop','start']:
    c=s['containers'].get(a[1]);rc=0 if c else 1
    if mode=='stop_fail' and a[0]=='stop':rc=1
    elif c:c['running']=a[0]=='start'
elif a[0]=='run':
    name=a[a.index('--name')+1]
    s['containers'][name]={'kind':'new','running':True,'restarts':0,'env':'new'}
    if mode=='run_fail':rc=1
    if mode=='restart':s['containers'][name]['restarts']=1
    if mode=='signal':
        p.write_text(json.dumps(s));os.kill(os.getppid(),signal.SIGTERM);sys.exit(0)
    out='new-id'
elif a[0]=='exec':
    c=s['containers'].get(a[1],{})
    out='503' if (c.get('kind')=='new' and mode in ['unhealthy','rollback_bad']) or mode=='rollback_bad' else '200'
    if out=='503':c['running']=False
elif a[0]=='rm':s['containers'].pop(a[-1],None)
elif a[0]=='update':
    if mode=='update_fail':rc=1
    else:s['containers'][a[-1]]['restart_policy']='unless-stopped'
else:rc=99
p.write_text(json.dumps(s));print(out);sys.exit(rc)
'''

class DeploymentTests(unittest.TestCase):
    def run_case(self, mode='ok', previous=True, backup=False, image=IMAGE):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); (p/'docker').write_text(DOCKER);(p/'docker').chmod(0o755)
            containers={NAME:{'kind':'old','running':True,'restarts':4,'env':'old-secret-config'}} if previous else {}
            if backup:containers[NAME+'-rollback']={'kind':'old','running':False}
            state={'containers':containers,'calls':[]};(p/'state').write_text(json.dumps(state));(p/'env').write_text('')
            env={**os.environ,'PATH':str(p)+os.pathsep+os.environ['PATH'],'MOCK_STATE':str(p/'state'),
                 'MOCK_MODE':mode,'IMAGE_ID':image,'TARGET_ENV':'stage','ENV_FILE':str(p/'env'),
                 'SOURCE_JOB':'artel-orchestration-server/develop','SOURCE_BUILD':'7',
                 'SOURCE_COMMIT':'b'*40,'HEALTH_TIMEOUT_SECONDS':'5','HEALTH_INTERVAL_SECONDS':'0'}
            r=subprocess.run(['bash',str(SCRIPT)],env=env,text=True,capture_output=True,timeout=10)
            return r,json.loads((p/'state').read_text())

    def test_success_checks_three_responses_then_enables_restart(self):
        r,s=self.run_case();self.assertEqual(r.returncode,0,r.stderr)
        self.assertEqual(s['containers'][NAME]['kind'],'new');self.assertNotIn(NAME+'-rollback',s['containers'])
        self.assertEqual(len([c for c in s['calls'] if c[0]=='exec']),3)
        self.assertLess(next(i for i,c in enumerate(s['calls']) if c[0]=='exec'),next(i for i,c in enumerate(s['calls']) if c[0]=='update'))

    def test_failed_run_restores_original_container_config(self):
        r,s=self.run_case('run_fail');self.assertNotEqual(r.returncode,0)
        self.assertEqual(s['containers'][NAME]['env'],'old-secret-config');self.assertIn('ROLLBACK_OK',r.stderr)

    def test_unhealthy_returns_failure_even_after_successful_rollback(self):
        r,s=self.run_case('unhealthy');self.assertNotEqual(r.returncode,0);self.assertEqual(s['containers'][NAME]['kind'],'old')
        self.assertIn('ROLLBACK_OK',r.stderr)

    def test_restart_is_failure(self):
        r,s=self.run_case('restart');self.assertNotEqual(r.returncode,0);self.assertEqual(s['containers'][NAME]['kind'],'old')

    def test_restart_policy_update_failure_rolls_back(self):
        r,s=self.run_case('update_fail');self.assertNotEqual(r.returncode,0);self.assertEqual(s['containers'][NAME]['kind'],'old')

    def test_first_deploy_failure_removes_new_container(self):
        r,s=self.run_case('unhealthy',previous=False);self.assertNotEqual(r.returncode,0);self.assertNotIn(NAME,s['containers'])

    def test_rollback_unhealthy_is_reported_as_failure(self):
        r,s=self.run_case('rollback_bad');self.assertNotEqual(r.returncode,0);self.assertIn('ROLLBACK_UNHEALTHY',r.stderr)

    def test_signal_rolls_back(self):
        r,s=self.run_case('signal');self.assertEqual(r.returncode,143);self.assertEqual(s['containers'][NAME]['kind'],'old')

    def test_label_mismatch_does_not_stop_old_container(self):
        r,s=self.run_case('wrong_label');self.assertEqual(r.returncode,2)
        self.assertFalse(any(c[0] in ['rename','stop','run'] for c in s['calls']))

    def test_invalid_image_id_does_not_call_docker(self):
        r,s=self.run_case(image='latest');self.assertEqual(r.returncode,2);self.assertEqual(s['calls'],[])

    def test_unresolved_backup_is_not_destroyed(self):
        r,s=self.run_case(backup=True);self.assertEqual(r.returncode,2);self.assertTrue(s['containers'][NAME]['running'])
        self.assertIn(NAME+'-rollback',s['containers'])

    def test_rename_failure_preserves_old_container(self):
        r,s=self.run_case('rename_fail');self.assertNotEqual(r.returncode,0)
        self.assertTrue(s['containers'][NAME]['running'])
        self.assertFalse(any(c[0]=='run' for c in s['calls']))

    def test_stop_failure_restores_original_name(self):
        r,s=self.run_case('stop_fail');self.assertNotEqual(r.returncode,0)
        self.assertTrue(s['containers'][NAME]['running']);self.assertNotIn(NAME+'-rollback',s['containers'])
        self.assertIn('ROLLBACK_OK',r.stderr)

if __name__=='__main__':unittest.main()
