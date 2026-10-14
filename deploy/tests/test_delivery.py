"""Exercise deployment ordering with external GitHub/Cloudflare/SSH tools replaced."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class DeliveryTest(unittest.TestCase):
    def run_delivery(self, failure=""):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            binaries = root / "bin"
            binaries.mkdir()
            for name in ["git", "ssh", "scp", "tar", "curl", "wrangler"]:
                binary = binaries / name
                binary.write_text('''#!/usr/bin/env bash
set -eu
name=$(basename "$0")
printf '%s\\n' "$name $*" >> "$CALLS"
if [[ "$name" == git ]]; then
  if [[ "$FAILURE" == stale ]]; then printf '%040d\\trefs/heads/main\\n' 0;
  else printf '%s\\trefs/heads/main\\n' "$GITHUB_SHA"; fi
fi
if [[ "$name" == ssh && "$*" == *"bash"* && "$FAILURE" == backend ]]; then exit 1; fi
if [[ "$name" == wrangler && "$FAILURE" == pages ]]; then exit 1; fi
if [[ "$name" == curl ]]; then printf '<app-root></app-root>'; fi
''')
                binary.chmod(0o755)
            (root / "pages").mkdir()
            (root / "pages/index.html").write_text("<app-root></app-root>")
            env = dict(os.environ, PATH=f"{binaries}:{os.environ['PATH']}",
                       FAILURE=failure, CALLS=str(root / "calls"), RUNNER_TEMP=str(root),
                       GITHUB_REF="refs/heads/main", GITHUB_SHA="a" * 40,
                       GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1", SSH_HOST="192.0.2.1",
                       SSH_USER="deploy", SSH_KEY="disposable-key", KNOWN_HOSTS="disposable-host-key",
                       BACKEND_IMAGE="ghcr.io/example/backend@sha256:" + "b" * 64,
                       CLOUDFLARE_ACCOUNT_ID="c" * 32, CLOUDFLARE_API_TOKEN="disposable-token",
                       CLOUDFLARE_PAGES_PROJECT="spliteasy", PAGES_DIR=str(root / "pages"),
                       WRANGLER_BIN=str(binaries / "wrangler"))
            if failure == "branch":
                env["GITHUB_REF"] = "refs/heads/dev"
            result = subprocess.run(["bash", str(REPO / "deploy/deliver.sh")], cwd=root,
                                    env=env, capture_output=True, text=True)
            calls = (root / "calls").read_text() if (root / "calls").exists() else ""
            return result, calls

    def test_backend_success_precedes_pages_and_pages_failure_never_rolls_back(self):
        for failure in ["", "pages"]:
            with self.subTest(failure=failure):
                result, calls = self.run_delivery(failure)
                self.assertEqual(result.returncode == 0, not failure, result.stderr)
                self.assertLess(calls.index("bash"), calls.index("wrangler pages deploy"))
                self.assertIn("--branch=main", calls)
                self.assertNotIn("rollback", calls)

    def test_backend_failure_blocks_pages(self):
        result, calls = self.run_delivery("backend")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("wrangler", calls)

    def test_dev_or_superseded_revision_has_no_production_effects(self):
        for failure in ["branch", "stale"]:
            with self.subTest(failure=failure):
                result, calls = self.run_delivery(failure)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn("ssh", calls)
                self.assertNotIn("wrangler", calls)


if __name__ == "__main__":
    unittest.main()
