"""Check Linux launcher orchestration without starting containers or a GUI."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LinuxLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fixflow launcher ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in ("start.sh", "stop.sh"):
            shutil.copy2(ROOT / name, self.root / name)
        self.example = "SERVER_PORT=18082\nFIXFLOW_API_URL=http://127.0.0.1:8080\n"
        self.root.joinpath(".env.example").write_text(self.example)
        self.tools = self.root / "tools"
        self.tools.mkdir()
        dispatcher = self.tools / "dispatch"
        dispatcher.write_text("#!" + sys.executable + "\n" + '''
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['FIXFLOW_TEST_CALLS'], 'a') as log:
    log.write(json.dumps([name, args, os.environ.get('FIXFLOW_API_URL'),
                          os.environ.get('MAVEN_OPTS')]) + '\\n')
if name == 'java': print('openjdk ' + os.environ.get('FIXFLOW_TEST_JAVA', '21.0.1'))
elif name == 'javac': print('javac ' + os.environ.get('FIXFLOW_TEST_JAVA', '21.0.1'))
elif name == 'docker':
    if args[0] == 'info': sys.exit(int(os.environ.get('FIXFLOW_TEST_DOCKER_ERROR', '0')))
    if args[:2] == ['compose', 'up']: sys.exit(int(os.environ.get('FIXFLOW_TEST_UP_ERROR', '0')))
    if args[:2] == ['compose', 'port']: print('127.0.0.1:18082')
elif name == 'curl': sys.exit(int(os.environ.get('FIXFLOW_TEST_HEALTH_ERROR', '0')))
''')
        dispatcher.chmod(0o755)
        for name in ("java", "javac", "docker", "curl"):
            self.tools.joinpath(name).symlink_to(dispatcher)
        self.root.joinpath("mvnw").symlink_to(dispatcher)
        self.env = dict(os.environ, PATH=str(self.tools) + os.pathsep + os.environ['PATH'],
                        DISPLAY=":test", FIXFLOW_TEST_CALLS=str(self.root / "calls.jsonl"))
        for name in ("JAVA_HOME", "MAVEN_USER_HOME", "MAVEN_OPTS", "SERVER_PORT", "FIXFLOW_API_URL"):
            self.env.pop(name, None)

    def launch(self, option=None, script="start.sh"):
        args = ["bash", str(self.root / script)]
        if option:
            args.append(option)
        result = subprocess.run(args, env=self.env, cwd=self.root.parent,
                                capture_output=True, text=True, timeout=20)
        log = self.root / "calls.jsonl"
        self.calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return result

    def test_full_launch_waits_for_services_then_prepares_desktop_and_opens_window(self):
        result = self.launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c[0] for c in self.calls],
                         ['java', 'javac', 'docker', 'docker', 'docker', 'curl', 'mvnw', 'mvnw'])
        self.assertIn('--wait', self.calls[3][1])
        self.assertEqual(self.calls[5][1][-1], 'http://127.0.0.1:18082/actuator/health/readiness')
        self.assertIn('-am', self.calls[6][1])
        self.assertIn('install', self.calls[6][1])
        self.assertIn('javafx:run', self.calls[7][1])
        self.assertEqual(self.calls[7][2], 'http://127.0.0.1:18082')
        self.assertEqual(self.root.joinpath('.env').read_text(), self.example)

    def test_missing_docker_stops_before_building_or_opening_desktop(self):
        self.env['FIXFLOW_TEST_DOCKER_ERROR'] = '1'
        result = self.launch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Docker daemon is unavailable', result.stderr)
        self.assertEqual([c[0] for c in self.calls], ['java', 'javac', 'docker'])

    def test_compose_failure_prevents_desktop_launch(self):
        self.env['FIXFLOW_TEST_UP_ERROR'] = '17'
        result = self.launch()
        self.assertEqual(result.returncode, 17)
        self.assertFalse(any(c[0] == 'mvnw' for c in self.calls))

    def test_failed_readiness_prevents_desktop_launch(self):
        self.env['FIXFLOW_TEST_HEALTH_ERROR'] = '22'
        result = self.launch('--desktop-only')
        self.assertEqual(result.returncode, 22)
        self.assertFalse(any(c[0] in ('mvnw', 'docker') for c in self.calls))

    def test_desktop_only_preserves_settings_and_treats_values_as_data(self):
        marker = self.root / 'must-not-exist'
        settings = self.example + f'UNRELATED=$(touch "{marker}")\nMAVEN_OPTS="$(touch forbidden) -Xmx512m"\n'
        self.root.joinpath('.env').write_text(settings)
        result = self.launch('--desktop-only')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(c[0] == 'docker' for c in self.calls))
        self.assertEqual(self.root.joinpath('.env').read_text(), settings)
        self.assertFalse(marker.exists())
        self.assertFalse(self.root.joinpath('forbidden').exists())
        self.assertEqual(self.calls[-1][3], '$(touch forbidden) -Xmx512m')

    def test_stop_only_stops_services_without_creating_settings(self):
        self.env.pop('DISPLAY', None)
        result = self.launch(script='stop.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(c[0], c[1]) for c in self.calls], [('docker', ['compose', 'stop'])])
        self.assertFalse(self.root.joinpath('.env').exists())

    def test_services_only_needs_neither_graphics_nor_java(self):
        self.env.pop('DISPLAY', None)
        self.env['FIXFLOW_TEST_JAVA'] = '17.0.1'
        result = self.launch('--services-only')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c[0] for c in self.calls], ['docker', 'docker'])
        self.assertIn('--wait', self.calls[-1][1])

    def test_wrong_java_version_is_rejected_before_starting_services(self):
        self.env['FIXFLOW_TEST_JAVA'] = '17.0.1'
        result = self.launch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Install JDK 21', result.stderr)
        self.assertFalse(any(c[0] in ('docker', 'mvnw') for c in self.calls))


if __name__ == '__main__':
    unittest.main(verbosity=2)
