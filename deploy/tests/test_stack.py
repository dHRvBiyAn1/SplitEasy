"""Check the real Compose boundary without production credentials."""
import json
import os
from pathlib import Path
import subprocess
import unittest

REPO = Path(__file__).resolve().parents[2]
TEST_ENV = dict(os.environ, POSTGRES_PASSWORD="disposable-password",
                APP_JWT_SECRET="disposable-key-at-least-32-bytes-long",
                PUBLIC_ORIGIN="https://split-easy.io", SITE_ADDRESS=":80",
                TUNNEL_TOKEN_FILE="/dev/null")


class StackTest(unittest.TestCase):
    def config(self, ci=False, extra_env=None):
        command = ["docker", "compose", "-f", "compose.prod.yml"]
        if ci:
            command += ["-f", "deploy/compose.ci.yml"]
        result = subprocess.run(command + ["config", "--format", "json"],
                                cwd=REPO, env=dict(TEST_ENV, **(extra_env or {})), capture_output=True, text=True, check=True)
        return json.loads(result.stdout)

    def test_native_smtp_outbound_network_requires_explicit_opt_in(self):
        closed = self.config(extra_env={"APP_MAIL_EGRESS_INTERNAL": "true"})
        self.assertIn("mail_egress", closed["services"]["backend"]["networks"])
        self.assertTrue(closed["networks"]["mail_egress"]["internal"])
        opened = self.config(extra_env={"APP_MAIL_EGRESS_INTERNAL": "false"})
        self.assertFalse(opened["networks"]["mail_egress"].get("internal", False))
        self.assertTrue(opened["networks"]["app"]["internal"])
        self.assertTrue(opened["networks"]["database"]["internal"])
        for name, service in opened["services"].items():
            if name != "backend":
                self.assertNotIn("mail_egress", service["networks"])
            self.assertFalse(service.get("ports"), name)
        mail = opened["services"]["backend"]["environment"]
        self.assertEqual(mail["APP_MAIL_ALLOW_INSECURE_LOCAL"], "false")
        self.assertEqual(mail["SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED"], "true")
        self.assertEqual(mail["SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_CHECKSERVERIDENTITY"], "true")

    def test_production_has_no_published_application_ports(self):
        for name, service in self.config()["services"].items():
            self.assertFalse(service.get("ports"), name)

    def test_connector_and_proxy_cannot_reach_the_database_network(self):
        config = self.config()
        self.assertTrue(config["networks"]["database"]["internal"])
        for service in ["caddy", "cloudflared"]:
            self.assertNotIn("database", config["services"][service]["networks"])
        self.assertEqual(config["services"]["backend"]["environment"]["APP_CORS_ALLOWED_ORIGINS"],
                         "https://split-easy.io")
        token = next(v for v in config["services"]["cloudflared"]["volumes"]
                     if v["target"] == "/run/secrets/tunnel-token")
        self.assertTrue(token["read_only"])
        # Some Compose versions omit false booleans in normalized JSON.
        self.assertFalse(token["bind"].get("create_host_path", False))

    def test_ci_exposes_only_a_loopback_proxy_and_skips_the_real_tunnel(self):
        config = self.config(ci=True)
        self.assertNotIn("cloudflared", config["services"])
        ports = config["services"]["caddy"]["ports"]
        self.assertEqual([(p["host_ip"], p["published"]) for p in ports], [("127.0.0.1", "18080")])

    def test_invalid_smtp_network_boolean_is_rejected(self):
        env = dict(TEST_ENV, APP_MAIL_EGRESS_INTERNAL="not-a-boolean")
        result = subprocess.run(["docker", "compose", "-f", "compose.prod.yml", "config", "--quiet"],
                                cwd=REPO, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)

    def test_missing_jwt_is_rejected(self):
        env = dict(TEST_ENV, APP_JWT_SECRET="")
        result = subprocess.run(["docker", "compose", "-f", "compose.prod.yml", "config", "--quiet"],
                                cwd=REPO, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
