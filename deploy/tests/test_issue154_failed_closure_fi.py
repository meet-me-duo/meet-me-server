"""Independent safety mutations in disposable copies; never invoke AWS or SSM."""
import hashlib
import copy
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from test_issue154_failed_operation_closure import (
    PIPELINE, reviewed_plan, intended_records, FakeClosureAdapter, operation_id, context,
)


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = "test_issue154_failed_operation_closure.py"
MUTATIONS = {
    "approval_bypass": (
        '    _require(type(approval_digest) is str and approval_digest == plan["approvalDigest"])',
        "    pass  # FI: omit exact approval",
        "test_apply_rejects_unapproved_or_tampered_plan_before_mutation",
    ),
    "terminal_failed_bypass": (
        '    _require(value["commandStatus"] == "Failed" and value["workflowStatus"] == "completed")',
        '    _require(value["workflowStatus"] == "completed")',
        "test_plan_rejects_every_non_failed_command_and_nonterminal_original_workflow",
    ),
    "workflow_terminal_bypass": (
        '    _require(value["commandStatus"] == "Failed" and value["workflowStatus"] == "completed")',
        '    _require(value["commandStatus"] == "Failed")',
        "test_plan_rejects_every_non_failed_command_and_nonterminal_original_workflow",
    ),
    "pending_count_bypass": (
        '    _require(type(value["pendingCommands"]) is int and value["pendingCommands"] == 0)',
        "    pass  # FI: ignore active commands",
        "test_plan_rejects_every_non_failed_command_and_nonterminal_original_workflow",
    ),
    "untrusted_guard_accepted": (
        'host["guardTrusted"] is True', "True", "test_plan_requires_full_exact_baseline_not_health_alone",
    ),
    "history_mismatch_accepted": (
        'host["historyMatches"] is True', "True", "test_plan_requires_full_exact_baseline_not_health_alone",
    ),
    "typed_json_equality_regression": (
        "return _canonical(left) == _canonical(right)", "return left == right",
        "test_persisted_numeric_aliases_are_not_exact_approved_terminal_records",
    ),
    "journal_snapshot_bypass": (
        '    _require(_closure_equal(snapshot["journal"], plan["originalJournal"]) '
        'and snapshot["journalEtag"] == plan["originalJournalEtag"])',
        "    pass  # FI: ignore journal changes",
        "test_delayed_old_journal_write_after_fence_is_detected_not_erased",
    ),
    "recovery_fence_reuses_old_holder": (
        'return dict(plan["originalOwner"], holder=plan["recoveryHolder"], released=released)',
        'return dict(plan["originalOwner"], released=released)',
        "test_valid_closure_preserves_failed_journal_and_commits_exact_sidecars",
    ),
    "post_commit_proof_not_rechecked": (
        '        snapshot = _closure_checkpoint(adapter, plan)\n'
        '        if snapshot["owner"] == _closure_owner(plan):',
        '        if snapshot["owner"] == _closure_owner(plan):',
        "test_closure_commit_is_not_permission_to_release_after_proof_changes",
    ),
    "approval_plan_digest_not_validated": (
        '    _require(plan["approvalDigest"] == _closure_hash('
        '{key: value for key, value in plan.items() if key != "approvalDigest"}))',
        "    pass  # FI: omit approved plan integrity",
        "test_apply_rejects_unapproved_or_tampered_plan_before_mutation",
    ),
    "changed_valid_proof_accepted": (
        '    _require(observed == plan["evidence"])',
        "    pass  # FI: accept a different valid baseline",
        "test_fresh_proof_change_before_apply_cannot_be_reapproved_implicitly",
    ),
}


class FailedClosureIndependentFaultInjection(unittest.TestCase):
    def test_adapter_boundary_guards_detect_independent_safety_mutations(self):
        source = ROOT / "deploy/pipeline/release_pipeline.py"
        original = source.read_bytes()
        text = original.decode("utf-8").replace("\r\n", "\n")
        mutations = {
            "typed_equality": (
                "return _canonical(left) == _canonical(right)", "return left == right", 1,
                "test_direct_sidecar_and_owner_writers_reject_numeric_type_aliases",
            ),
            "skip_fence": (
                '        _require(_closure_equal(snapshot["owner"], _closure_owner(plan)) '
                'and snapshot["closure"] is None)', "        pass  # FI: commit without fence", 1,
                "test_commit_cannot_skip_the_owner_recovery_fence",
            ),
            "ignore_journal": (
                '    _require(_closure_equal(snapshot["journal"], plan["originalJournal"]) '
                'and snapshot["journalEtag"] == plan["originalJournalEtag"])',
                "    pass  # FI: ignore changed journal", 1,
                "test_fence_rechecks_original_journal_and_owner_etags",
            ),
            "wrong_context": (
                'and self.closure_context() == plan["target"]', "and True", 4,
                "test_direct_writers_cannot_use_another_account_context",
            ),
        }
        scratch = ROOT / ".codex/tdd-evidence"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="closure-adapter-fi-", dir=scratch) as directory:
            root = Path(directory).resolve()
            self.assertTrue(root.is_relative_to(scratch.resolve()))
            (root / "deploy/pipeline").mkdir(parents=True)
            (root / "deploy/tests").mkdir()
            for module in (ROOT / "deploy/pipeline").glob("*.py"):
                shutil.copyfile(module, root / "deploy/pipeline" / module.name)
            for name in [CONTRACT, Path(__file__).name]:
                shutil.copyfile(ROOT / "deploy/tests" / name, root / "deploy/tests" / name)
            target = root / "deploy/pipeline/release_pipeline.py"
            for name, mutation in [("control", None), *mutations.items()]:
                with self.subTest(mutation=name):
                    if mutation is not None:
                        before, after, count, detector = mutation
                        self.assertEqual(count, text.count(before), "The intended adapter seam changed: " + name)
                        candidate = text.replace(before, after)
                    else:
                        candidate = text
                    target.write_text(candidate, encoding="utf-8")
                    cache = target.parent / "__pycache__"
                    if cache.exists():
                        self.assertTrue(cache.resolve().is_relative_to(root))
                        shutil.rmtree(cache)
                    result = subprocess.run(
                        [sys.executable, "-X", "utf8", "-m", "unittest", "discover", "-s", "deploy/tests",
                         "-p", Path(__file__).name, "-k", "AdapterBoundary", "-v"],
                        cwd=root, capture_output=True, text=True, encoding="utf-8", timeout=60,
                    )
                    output = result.stdout + result.stderr
                    self.assertIn("Ran 5 tests", output)
                    if mutation is None:
                        self.assertEqual(0, result.returncode, output[-5000:])
                    else:
                        self.assertNotEqual(0, result.returncode, "Survived adapter mutation: " + name)
                        self.assertIn("FAIL: " + detector, output, output[-5000:])
                        self.assertNotIn("errors=", output)
        self.assertEqual(hashlib.sha256(original).digest(), hashlib.sha256(source.read_bytes()).digest())

    def test_safety_mutants_are_detected_by_frozen_independent_contracts(self):
        source = ROOT / "deploy/pipeline/release_pipeline.py"
        contracts = ROOT / "deploy/tests" / CONTRACT
        original_source = source.read_bytes()
        original_tests = contracts.read_bytes()
        text = original_source.decode("utf-8").replace("\r\n", "\n")
        scratch = ROOT / ".codex/tdd-evidence"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="closure-independent-fi-", dir=scratch) as directory:
            root = Path(directory).resolve()
            self.assertTrue(root.is_relative_to(scratch.resolve()))
            (root / "deploy/pipeline").mkdir(parents=True)
            (root / "deploy/tests").mkdir()
            for module in (ROOT / "deploy/pipeline").glob("*.py"):
                shutil.copyfile(module, root / "deploy/pipeline" / module.name)
            (root / "deploy/tests" / CONTRACT).write_bytes(original_tests)
            target = root / "deploy/pipeline/release_pipeline.py"

            def execute(content):
                target.write_text(content, encoding="utf-8")
                cache = target.parent / "__pycache__"
                if cache.exists():
                    self.assertTrue(cache.resolve().is_relative_to(root))
                    shutil.rmtree(cache)
                return subprocess.run(
                    [sys.executable, "-X", "utf8", "-m", "unittest", "discover", "-s", "deploy/tests",
                     "-p", CONTRACT, "-v"],
                    cwd=root, capture_output=True, text=True, encoding="utf-8", timeout=60,
                )

            control = execute(text)
            self.assertEqual(0, control.returncode, (control.stdout + control.stderr)[-5000:])
            self.assertIn("Ran 26 tests", control.stderr)
            for name, (before, after, detector) in MUTATIONS.items():
                with self.subTest(mutation=name):
                    self.assertEqual(1, text.count(before), "The intended mutation seam changed: " + name)
                    result = execute(text.replace(before, after, 1))
                    output = result.stdout + result.stderr
                    self.assertNotEqual(0, result.returncode, "Survived safety mutation: " + name)
                    self.assertIn("Ran 26 tests", output)
                    self.assertIn("FAIL: " + detector, output, output[-5000:])
                    self.assertNotIn("errors=", output)
        self.assertEqual(hashlib.sha256(original_source).digest(), hashlib.sha256(source.read_bytes()).digest())
        self.assertEqual(hashlib.sha256(original_tests).digest(), hashlib.sha256(contracts.read_bytes()).digest())


class FailedClosureAdapterBoundaryContracts(unittest.TestCase):
    """Exercise real conditional-writer guards independently of the engine."""
    def setUp(self):
        self.store = FakeClosureAdapter()
        self.plan = reviewed_plan()
        self.intent, self.closure, self.fenced, self.released = intended_records(self.plan)
        self.writes = []
        self.adapter = object.__new__(PIPELINE.AwsReleaseAdapter)
        self.adapter.root_key = "synthetic-closure/"
        self.adapter.owner_key = self.adapter.root_key + "owner.json"
        self.adapter.closure_context = lambda: copy.deepcopy(self.store.target)
        self.adapter._read_object = self.read_object
        self.adapter._write_object = self.write_object

    def read_object(self, key):
        stem = self.adapter.root_key + operation_id()
        if key == stem + ".json":
            return copy.deepcopy(self.store.snapshot["journal"]), self.store.snapshot["journalEtag"]
        if key == self.adapter.owner_key:
            return copy.deepcopy(self.store.snapshot["owner"]), self.store.snapshot["ownerEtag"]
        if key == stem + ".closure-intent.json":
            return copy.deepcopy(self.store.snapshot["intent"]), '"intent-etag"'
        if key == stem + ".closure.json":
            return copy.deepcopy(self.store.snapshot["closure"]), '"closure-etag"'
        raise AssertionError("Unexpected synthetic object key")

    def write_object(self, key, value, etag):
        self.writes.append((key, copy.deepcopy(value), etag))
        if key == self.adapter.owner_key:
            self.store.write_owner(operation_id(), value, etag)
        else:
            field = "intent" if key.endswith(".closure-intent.json") else "closure"
            if self.store.snapshot[field] is not None or etag is not None:
                raise RuntimeError("CONDITIONAL_WRITE_FAILED")
            self.store.snapshot[field] = copy.deepcopy(value)

    def test_supported_writer_prefixes_use_immutable_sidecars_and_owner_cas(self):
        self.adapter.put_closure_intent(operation_id(), self.intent)
        self.adapter.fence_closure_owner(operation_id(), self.fenced, '"owner-1"')
        self.adapter.put_failed_closure(operation_id(), self.closure)
        self.adapter.release_closure_owner(operation_id(), self.released, self.store.snapshot["ownerEtag"])
        self.assertEqual([None, '"owner-1"', None, '"owner-2"'], [write[2] for write in self.writes])
        self.assertEqual(self.released, self.store.snapshot["owner"])
        self.assertEqual(self.store.initial_journal, self.store.snapshot["journal"])

    def test_direct_sidecar_and_owner_writers_reject_numeric_type_aliases(self):
        for port in ["fence", "commit", "release"]:
            with self.subTest(port=port):
                self.setUp()
                self.store.snapshot["intent"] = copy.deepcopy(self.intent)
                if port == "fence":
                    value = self.fenced | {"released": 0}
                    action = lambda: self.adapter.fence_closure_owner(operation_id(), value, '"owner-1"')
                else:
                    self.store.snapshot.update(owner=copy.deepcopy(self.fenced), ownerEtag='"owner-2"')
                    if port == "commit":
                        value = self.closure | {"deploymentComplete": 0}
                        action = lambda: self.adapter.put_failed_closure(operation_id(), value)
                    else:
                        self.store.snapshot["closure"] = copy.deepcopy(self.closure)
                        value = self.released | {"released": 1}
                        action = lambda: self.adapter.release_closure_owner(operation_id(), value, '"owner-2"')
                with self.assertRaises(ValueError):
                    action()
                self.assertEqual([], self.writes)

    def test_commit_cannot_skip_the_owner_recovery_fence(self):
        self.store.snapshot["intent"] = copy.deepcopy(self.intent)
        with self.assertRaises(ValueError):
            self.adapter.put_failed_closure(operation_id(), self.closure)
        self.assertEqual([], self.writes)

    def test_fence_rechecks_original_journal_and_owner_etags(self):
        for changed in ["journal", "journalEtag", "ownerEtag"]:
            with self.subTest(changed=changed):
                self.setUp(); self.store.snapshot["intent"] = copy.deepcopy(self.intent)
                if changed == "journal":
                    self.store.snapshot["journal"]["sequence"] = 5
                else:
                    self.store.snapshot[changed] = '"concurrent-snapshot"'
                with self.assertRaises(ValueError):
                    self.adapter.fence_closure_owner(operation_id(), self.fenced, '"owner-1"')
                self.assertEqual([], self.writes)

    def test_direct_writers_cannot_use_another_account_context(self):
        for port in ["intent", "fence", "commit", "release"]:
            with self.subTest(port=port):
                self.setUp()
                self.store.target = context() | {"accountId": "999999999999"}
                if port == "intent":
                    action = lambda: self.adapter.put_closure_intent(operation_id(), self.intent)
                else:
                    self.store.snapshot["intent"] = copy.deepcopy(self.intent)
                    if port == "fence":
                        action = lambda: self.adapter.fence_closure_owner(operation_id(), self.fenced, '"owner-1"')
                    else:
                        self.store.snapshot.update(owner=copy.deepcopy(self.fenced), ownerEtag='"owner-2"')
                        if port == "commit":
                            action = lambda: self.adapter.put_failed_closure(operation_id(), self.closure)
                        else:
                            self.store.snapshot["closure"] = copy.deepcopy(self.closure)
                            action = lambda: self.adapter.release_closure_owner(operation_id(), self.released, '"owner-2"')
                with self.assertRaises(ValueError):
                    action()
                self.assertEqual([], self.writes)


class FailedClosureProofBoundaryContracts(unittest.TestCase):
    def test_complete_actual_and_automatic_contract_histories_require_exact_json_types(self):
        # Import inside this test: disposable writer-FI copies intentionally do
        # not load the unrelated integration fixtures or execute their tests.
        from test_issue154_closure_integration import (
            EvidenceDriver, release as synthetic_release, identity as observed_identity,
            COMMAND as observed_command,
        )

        scratch = ROOT / ".codex/tdd-evidence"
        scratch.mkdir(parents=True, exist_ok=True)
        for location in ["actual", "automatic-contract"]:
            for field in ["checksum", "success"]:
                with self.subTest(location=location, field=field):
                    with tempfile.TemporaryDirectory(prefix="closure-proof-types-", dir=scratch) as directory:
                        driver = EvidenceDriver()
                        adapter = PIPELINE.AwsReleaseAdapter(synthetic_release(driver, directory), "preflight")
                        self.assertIs(True, adapter.closure_evidence(observed_identity(), observed_command)["hostProof"]["historyMatches"])
                        if location == "actual":
                            driver.facts["history"] = copy.deepcopy(driver.facts["history"])
                            rows = driver.facts["history"]
                        else:
                            driver.facts["automaticContract"]["history"] = copy.deepcopy(driver.facts["automaticContract"]["history"])
                            rows = driver.facts["automaticContract"]["history"]
                        rows[0][field] = float(rows[0][field]) if field == "checksum" else 1
                        with self.assertRaises(ValueError):
                            adapter.closure_evidence(observed_identity(), observed_command)
                        self.assertFalse([call for call in driver.calls if call[1] in
                                          {"send-command", "put-object", "enable-rule", "disable-rule"}])


if __name__ == "__main__":
    unittest.main()
