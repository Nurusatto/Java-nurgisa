"""Exercise the Docker JRE probe against real HTTP responses without requiring Docker."""
import http.server
import pathlib
import socket
import subprocess
import tempfile
import threading
import unittest


class ProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix="fixflow-probe-")
        source = pathlib.Path(__file__).resolve().parents[1] / "docker/Healthcheck.java"
        subprocess.run(["javac", "--release", "21", "-d", cls.build.name, str(source)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def probe(self, port):
        return subprocess.run(
            ["java", "-cp", self.build.name, "Healthcheck",
             f"http://127.0.0.1:{port}/actuator/health/readiness"],
            timeout=10, capture_output=True, check=False,
        )

    def check_http(self, status, expected_exit):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(status)
                self.end_headers()

            def log_message(self, *_):
                pass

        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = self.probe(server.server_port)
            self.assertEqual(expected_exit, result.returncode)
            self.assertEqual(b"", result.stdout)
            self.assertEqual(b"", result.stderr)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_ready_service_is_healthy(self):
        self.check_http(200, 0)

    def test_starting_service_is_not_healthy(self):
        self.check_http(503, 1)

    def test_unavailable_service_is_not_healthy(self):
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        self.assertEqual(1, self.probe(port).returncode)


if __name__ == "__main__":
    unittest.main()
