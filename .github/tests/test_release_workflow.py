import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
CHECKOUT = "actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0"
SETUP_JAVA = "actions/setup-java@0f481fcb613427c0f801b606911222b5b6f3083a"
GRADLE_CHECKS = (
    "./gradlew :app:testDebugUnitTest :server:test :app:lintDebug "
    ":app:assembleDebug :app:verifyComposeDependencyFamily "
    ":app:verifyNoServiceUrlConfiguration"
)


class ReleaseWorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(WORKFLOW.is_file(), f"missing workflow: {WORKFLOW}")
        self.workflow = WORKFLOW.read_text(encoding="utf-8")

    def assert_markers_in_order(self, *markers):
        positions = []
        for marker in markers:
            position = self.workflow.find(marker)
            self.assertNotEqual(-1, position, f"missing marker: {marker}")
            positions.append(position)
        self.assertEqual(sorted(positions), positions)

    def test_is_a_serialised_manual_release_with_write_permission(self):
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertRegex(self.workflow, r"(?m)^      version:\s*$")
        self.assertRegex(self.workflow, r"(?m)^      version_code:\s*$")
        self.assertRegex(self.workflow, r"(?m)^permissions:\s*\n  contents: write\s*$")
        self.assertIn("group: command-code-chat-release", self.workflow)
        self.assertIn("cancel-in-progress: false", self.workflow)

    def test_uses_only_the_approved_pinned_setup_actions(self):
        uses = re.findall(r"(?m)^\s*uses:\s*([^\s#]+)", self.workflow)
        self.assertEqual([CHECKOUT, SETUP_JAVA], uses)
        self.assertIn('java-version: "17"', self.workflow)
        self.assertIn("distribution: temurin", self.workflow)

    def test_checks_current_main_kotlin_metadata_and_one_clean_draft_before_building(self):
        self.assertIn('test "$GITHUB_REF" = "refs/heads/main"', self.workflow)
        self.assertIn('pathlib.Path("app/build.gradle.kts")', self.workflow)
        self.assertIn(r'versionName\s*=\s*"([^"]+)"', self.workflow)
        self.assertIn(r"versionCode\s*=\s*(\d+)", self.workflow)
        self.assertIn("Expected exactly one draft for $TAG", self.workflow)
        self.assertIn("Release must be a stable draft", self.workflow)
        self.assertIn("Draft release notes are empty", self.workflow)
        self.assertIn("Draft must not contain pre-existing assets", self.workflow)
        self.assert_markers_in_order(
            "- name: Require current main",
            "- name: Validate version metadata",
            "- name: Validate release draft",
            "- name: Test and build release APK",
        )

    def test_builds_inspects_and_uploads_the_exact_apk_contract(self):
        self.assertIn(
            "APK_PATH: app/build/outputs/apk/debug/app-debug.apk",
            self.workflow,
        )
        self.assertIn(GRADLE_CHECKS, self.workflow)
        self.assertIn("sha256sum \"$APK_PATH\"", self.workflow)
        self.assertIn("application/vnd.android.package-archive", self.workflow)
        self.assertIn("assets?name=CommandCodeChat.apk", self.workflow)
        self.assertIn('"name": "CommandCodeChat.apk"', self.workflow)
        self.assertIn('"digest": f"sha256:{os.environ[\'EXPECTED_SHA256\']}"', self.workflow)
        self.assert_markers_in_order(
            "- name: Inspect release APK",
            "- name: Pin release draft to commit",
            "- name: Upload CommandCodeChat.apk",
            "- name: Verify uploaded APK",
        )

    def test_creates_the_tag_before_publishing_and_verifies_the_final_release(self):
        self.assertIn('RELEASE_TITLE: Command Code Chat ${{ inputs.version }}', self.workflow)
        self.assertIn('-f ref="refs/tags/$TAG"', self.workflow)
        self.assertIn("Release must contain exactly one supplied asset", self.workflow)
        self.assertIn('releases/latest" --jq .id', self.workflow)
        self.assertIn('test "$(git rev-parse "$TAG^{commit}")" = "$GITHUB_SHA"', self.workflow)
        self.assert_markers_in_order(
            "- name: Create release tag",
            "- name: Publish release draft",
            "- name: Verify published release",
        )


if __name__ == "__main__":
    unittest.main()
