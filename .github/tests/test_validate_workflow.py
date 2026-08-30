import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "validate.yml"
CHECKOUT = "actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0"
SETUP_JAVA = "actions/setup-java@0f481fcb613427c0f801b606911222b5b6f3083a"
GRADLE_CHECKS = (
    "./gradlew :app:testDebugUnitTest :server:test :app:lintDebug "
    ":app:assembleDebug :app:verifyComposeDependencyFamily "
    ":app:verifyNoServiceUrlConfiguration"
)


class ValidateWorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(WORKFLOW.is_file(), f"missing workflow: {WORKFLOW}")
        self.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_runs_for_pushes_and_pull_requests_with_read_only_permissions(self):
        self.assertRegex(self.workflow, r"(?m)^  push:\s*$")
        self.assertRegex(self.workflow, r"(?m)^  pull_request:\s*$")
        self.assertRegex(self.workflow, r"(?m)^permissions:\s*\n  contents: read\s*$")
        self.assertNotIn("contents: write", self.workflow)
        self.assertIn("cancel-in-progress: true", self.workflow)

    def test_uses_only_the_approved_pinned_setup_actions(self):
        uses = re.findall(r"(?m)^\s*uses:\s*([^\s#]+)", self.workflow)
        self.assertEqual([CHECKOUT, SETUP_JAVA], uses)
        self.assertIn('java-version: "17"', self.workflow)
        self.assertIn("distribution: temurin", self.workflow)
        self.assertIn("cache: gradle", self.workflow)

    def test_runs_contract_tests_and_the_complete_gradle_validation(self):
        self.assertIn(
            "python3 -m unittest discover -s .github/tests -v",
            self.workflow,
        )
        self.assertIn(GRADLE_CHECKS, self.workflow)


if __name__ == "__main__":
    unittest.main()
