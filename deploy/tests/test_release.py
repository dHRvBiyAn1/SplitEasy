"""Exercise release failure boundaries without a VPS or production credentials."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class ReleaseTest(unittest.TestCase):
    def run_release(self, failure="", through_alias=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            previous = root / "releases" / "previous"
            previous.mkdir(parents=True)
            (root / "current").symlink_to(previous)
            (root / ".env").write_text("server-config")
            token = root / "tunnel.token"
            token.write_text("" if failure == "token_empty" else "disposable-token")
            token.chmod(0o400)
            if failure == "token":
                token.unlink()
            elif failure == "token_public":
                token.chmod(0o644)
            binaries = root / "bin"
            binaries.mkdir()
            docker = binaries / "docker"
            docker.write_text('''#!/usr/bin/env bash
set -eu
[[ -z "${BACKEND_IMAGE:-}" && -z "${FRONTEND_IMAGE:-}" ]] || exit 1
printf '%s\\n' "$*" >> "$CALLS"
if [[ "$*" == *"config --quiet"* ]]; then [[ "$FAILURE" != config ]]; exit; fi
if [[ "$*" == *"run --rm"* && "$FAILURE" == token_permission ]]; then exit 1; fi
if [[ "$*" == *"exec -T backend curl"* && "$FAILURE" == readiness ]]; then exit 1; fi
if [[ "$*" == *"pull backend caddy cloudflared"* && "$FAILURE" == pull ]]; then exit 1; fi
if [[ "$*" == *"pg_dump"* ]]; then
  [[ "$FAILURE" != backup ]] || exit 1
  printf 'fake-dump'
fi
if [[ "$*" == *"up"* && "$*" == *"backend caddy cloudflared"* && "$FAILURE" == startup ]]; then exit 1; fi
if [[ "$*" == *"config --format json"* ]]; then printf '{"services":{"cloudflared":{"volumes":[{"target":"/run/secrets/tunnel-token","source":"%s"}]}},"x-api-origin":"%s"}' "$TOKEN_FILE" "${API_ORIGIN_TEST:-https://api.split-easy.io}"; fi
''')
            curl = binaries / "curl"
            curl.write_text('''#!/usr/bin/env bash
[[ "$FAILURE" != smoke ]] || exit 1
if [[ "$*" == *"/api/health"* ]]; then printf '{"status":"UP"}'; else printf '<app-root></app-root>'; fi
''')
            flock = binaries / "flock"
            flock.write_text("#!/usr/bin/env bash\nexit 0\n")
            for binary in binaries.iterdir():
                binary.chmod(0o755)
            # Script lives inside its real release package; only the external tools are stubbed.
            import shutil
            release = root / "releases" / ("a" * 40)
            (release / "deploy").mkdir(parents=True)
            for name in ["deploy.sh", "backup.sh"]:
                shutil.copy(REPO / "deploy" / name, release / "deploy" / name)
            shutil.copy(REPO / "compose.prod.yml", release / "compose.prod.yml")
            (release / "release.env").write_text(
                "BACKEND_IMAGE=ghcr.io/example/app-backend@sha256:" + "b" * 64 + "\n"

            )
            if failure in ["manifest", "manifest_no_newline"]:
                with (release / "release.env").open("a") as file:
                    file.write("BACKEND_IMAGE=untrusted:tag" + ("\n" if failure == "manifest" else ""))
            script = release / "deploy/deploy.sh"
            if through_alias == "current":
                (root / "current").unlink()
                (root / "current").symlink_to(release)
                (root / "previous").symlink_to(previous)
                script = root / "current/deploy/deploy.sh"
            elif through_alias == "previous":
                (root / "previous").symlink_to(release)
                script = root / "previous/deploy/deploy.sh"
            env = dict(os.environ, SPLITEASY_ROOT=str(root), PATH=f"{binaries}:{os.environ['PATH']}",
                       FAILURE=failure, CALLS=str(root / "calls"), TOKEN_FILE=str(token),
                       API_ORIGIN_TEST="https://evil.example" if failure == "origin" else "https://api.split-easy.io",
                       BACKEND_IMAGE="untrusted:tag", FRONTEND_IMAGE="untrusted:tag")
            result = subprocess.run(["bash", str(script)], env=env,
                                    capture_output=True, text=True)
            calls = (root / "calls").read_text() if (root / "calls").exists() else ""
            active = (root / "current").resolve()
            if failure:
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(active, previous)
                if failure in ["pull", "backup"]:
                    self.assertNotIn("--wait-timeout 180 backend caddy cloudflared", calls)
            else:
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(active, release)
                self.assertEqual(Path(os.readlink(root / "current")), release)
                self.assertLess(calls.index("pg_dump"), calls.index("--wait-timeout 180 backend caddy cloudflared"))
                self.assertEqual((root / "previous").resolve(), previous)
            self.assertNotIn("down", calls)

    def test_success_backs_up_before_replacing_apps_and_activates_release(self):
        self.run_release()

    def test_failed_release_keeps_active_pointer(self):
        for failure in ["manifest", "manifest_no_newline", "config", "token", "token_empty", "token_public", "token_permission", "origin", "pull", "backup", "startup", "readiness", "smoke"]:
            with self.subTest(failure=failure):
                self.run_release(failure)

    def test_recovering_through_symlinks_preserves_immutable_release_path(self):
        for alias in ["current", "previous"]:
            with self.subTest(alias=alias):
                self.run_release(through_alias=alias)


if __name__ == "__main__":
    unittest.main()
