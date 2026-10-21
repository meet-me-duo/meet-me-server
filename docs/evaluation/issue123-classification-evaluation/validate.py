"""Validate preregistered assets locally. No model, HTTP, Gradle or semantic regex."""
import argparse
import hashlib
import json
from pathlib import Path

PINNED = {'docs/fixtures/issue-107-decision-development.json': '34f7ad4f06120c6cba6e1869e3e9ef5a9b250e1687765367ae28be96be7323a0', 'docs/fixtures/issue-107-decision-holdout.json': 'da6569ee860561dd0d1cb2019ec142bea5b0f8d649f81964ebc3ac0f7ec7c407', 'docs/fixtures/issue-123-ui-evaluation-development-overlay.json': '337fd05e1b23435e8b9633e6518a25eab2abb5b3545288dfec224b82f7be779b', 'docs/fixtures/issue-123-ui-evaluation-holdout-overlay.json': '82460043b0ede12bba72d43285b94c4cc94854265f9822687a9d7aef32aad18b', 'docs/fixtures/issue-123-ui-evaluation-supplemental-pairs.json': '1bf192223986cc7b98885d98bb97cecdbda5ec5d6e3769104519dcd675b97472', 'docs/evaluation/issue123-classification-evaluation/review-summary.json': 'a35bf46f59b5f05cf743e5c7757dae9be67ca715e7e0eaea46ef58ee64894fac', 'docs/evaluation/issue123-classification-evaluation/scoring-contract.json': '66857bff4f5e68e0b36bd0d4c21f8991eecd3c12e7e7c4311fc8a87ec1109c4b'}
FINAL_STATUS = 'FINAL_PRE_EXECUTION_ORACLE'
A_GOLD = {'CLEAR', 'NEEDS_REVIEW', 'INDETERMINATE'}
B_VERDICTS = {'CLEAR', 'NEEDS_CHOICE', 'NEEDS_EDIT'}
POLICIES = {'MUST_NOT_ASK', 'SAFE_ABSTAIN_NO_FORCED_CHOICES',
            'MUST_CLARIFY_BEFORE_ACCEPTANCE', 'SAFE_EDIT_ONLY',
            'MUST_ASK_MATERIAL_CHOICE'}
A_ONLY_RULE = '분류만 수행하며 질문·수정 후보는 생성하지 않음'
CONTEXT = {'referenceAt': '2026-11-04T03:00:00Z', 'timeZone': 'Asia/Seoul',
           'locale': 'ko-KR', 'searchStartDate': '2026-11-04',
           'searchEndDate': '2026-11-18'}
ASSETS = {
    'development': 'docs/fixtures/issue-107-decision-development.json',
    'holdout': 'docs/fixtures/issue-107-decision-holdout.json',
    'development_overlay': 'docs/fixtures/issue-123-ui-evaluation-development-overlay.json',
    'holdout_overlay': 'docs/fixtures/issue-123-ui-evaluation-holdout-overlay.json',
    'supplemental': 'docs/fixtures/issue-123-ui-evaluation-supplemental-pairs.json',
}
PACKAGE = 'docs/evaluation/issue123-classification-evaluation/'


def require(condition, label):
    if not condition:
        raise ValueError(label)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_asset(root, relative, status=None):
    path = root / relative
    value = json.loads(path.read_text())
    # DRAFT rejection is explicit and precedes the hash check.
    if status is not None:
        require(value.get('status') == status, 'STATUS_NOT_FINAL: ' + relative)
    require(digest(path) == PINNED[relative], 'SHA_MISMATCH: ' + relative)
    return value


def behavior_partition(gold):
    require(gold['question_policy'] in POLICIES, 'POLICY_ENUM')
    target = set(gold['expected_behavior_verdicts'])
    incomplete = set(gold['safe_but_incomplete_verdicts'])
    forbidden = set(gold['forbidden_verdicts'])
    require(target | incomplete | forbidden == B_VERDICTS, 'VERDICT_PARTITION_NOT_EXHAUSTIVE')
    require(not (target & incomplete or target & forbidden or incomplete & forbidden), 'VERDICT_PARTITION_OVERLAP')
    require(set(gold['question_generation_success_verdicts']) <= {'NEEDS_CHOICE'}, 'SAFE_EDIT_IS_NOT_QUESTION_SUCCESS')
    require(gold['safe_abstention_completes_submission'] is False, 'SAFE_EDIT_IS_NOT_SUBMISSION_COMPLETION')
    require(bool(gold['must_preserve']), 'MUST_PRESERVE_MISSING')
    require(A_ONLY_RULE not in gold['must_preserve'], 'A_OUTPUT_RULE_LEAKED_TO_B')


def validate(root):
    ids, old_raw, public_secrets = set(), set(), []
    old_counts = {}
    for split in ['development', 'holdout']:
        source = load_asset(root, ASSETS[split])
        overlay = load_asset(root, ASSETS[split + '_overlay'], FINAL_STATUS)
        require(source['split'] == split, 'OLD_SPLIT_CHANGED')
        require(source['shared_context'] == CONTEXT, 'OLD_CONTEXT_CHANGED')
        require(source['model_input_allowlist'] == ['rawText', 'referenceAt', 'timeZone', 'locale', 'searchStartDate', 'searchEndDate'], 'MODEL_INPUT_ALLOWLIST')
        require(len(source['cases']) == len(overlay['cases']) == 60, 'OLD_COUNT_CHANGED')
        require(overlay['source_path'] == ASSETS[split], 'OVERLAY_SOURCE_PATH')
        require(overlay['source_sha256'] == PINNED[ASSETS[split]], 'OVERLAY_SOURCE_SHA')
        require(overlay['source_split_unchanged'] and overlay['primary_stage_a_gold_unchanged'], 'OVERLAY_SOURCE_SCOPE')
        for original, entry in zip(source['cases'], overlay['cases']):
            require(original['case_id'] == entry['case_id'], 'OVERLAY_CASE_ID')
            require(entry['case_id'] not in ids, 'DUPLICATE_ID')
            ids.add(entry['case_id'])
            require(1 <= len(original['raw_text']) <= 500 and '\0' not in original['raw_text'], 'OLD_RAW_BOUNDARY')
            old_raw.add(original['raw_text'])
            require(entry['stage_a_gold'] == original['oracle']['expected_decision'], 'PRIMARY_A_GOLD_CHANGED')
            require(entry['stage_a_gold'] in A_GOLD, 'A_GOLD_ENUM')
            require(entry['core_false_clear_gate'] == original['oracle']['core_false_clear_gate'], 'CORE_GATE_CHANGED')
            require(entry['source_raw_sha256'] == hashlib.sha256(original['raw_text'].encode()).hexdigest(), 'RAW_SHA_CHANGED')
            require(entry['source_stage_a_preserve_provenance'] == original['oracle']['must_preserve'], 'A_PRESERVE_PROVENANCE_CHANGED')
            bg = entry['stage_b_gold']
            require(bg['must_preserve'] == [v for v in original['oracle']['must_preserve'] if v != A_ONLY_RULE], 'B_SOURCE_CONDITION_CHANGED')
            require(bg['source_reason'] == original['oracle']['reason'], 'B_SOURCE_REASON_CHANGED')
            require(entry['pair_id'] is None, 'UNCERTIFIED_OLD_PAIR_ASSIGNED')
            behavior_partition(bg)
            if split == 'holdout':
                public_secrets += [original['raw_text'], original['case_id'], original['oracle']['reason']]
        old_counts[split] = len(source['cases'])
    sup = load_asset(root, ASSETS['supplemental'], FINAL_STATUS)
    require(sup['shared_context'] == CONTEXT, 'SUPPLEMENT_CONTEXT_CHANGED')
    require(sup['model_input_allowlist'] == ['rawText', 'referenceAt', 'timeZone', 'locale', 'searchStartDate', 'searchEndDate'], 'SUPPLEMENT_INPUT_ALLOWLIST')
    require(sup['partition'] == 'SUPPLEMENTAL_UNTUNED_BLIND_ALL_16' and sup['not_part_of_old_splits'], 'SUPPLEMENT_SPLIT')
    require(sup['model_execution_count'] == 0, 'ORACLE_IS_NOT_MODEL_RESULTS')
    require(len(sup['cases']) == 16 and len(sup['pairs']) == 8, 'SUPPLEMENT_COUNT')
    case = {c['case_id']: c for c in sup['cases']}
    require(len(case) == 16 and len({c['raw_text'] for c in case.values()}) == 16, 'SUPPLEMENT_DUPLICATE')
    dimensions = {'DATE', 'TIME', 'PLACE', 'REFERENT', 'NEGATION', 'EXCEPTION', 'DEPENDENCY', 'FLEXIBLE_CONDITION'}
    require({p['dimension'] for p in sup['pairs']} == dimensions, 'PAIR_CATEGORY_COVERAGE')
    for c in case.values():
        require(c['case_id'] not in ids, 'DUPLICATE_ID')
        ids.add(c['case_id'])
        require(c['raw_text'] not in old_raw, 'SUPPLEMENT_OLD_EXACT_DUPLICATE')
        require(1 <= len(c['raw_text']) <= 500 and '\0' not in c['raw_text'], 'RAW_BOUNDARY')
        require(not any(k in c for k in CONTEXT), 'CASE_CONTEXT_OVERRIDE')
        ag, bg = c['oracle']['stage_a'], c['oracle']['stage_b']
        require(ag['expected_decision'] in A_GOLD and ag['confidence_target'] is None, 'A_GOLD_OR_CONFIDENCE_TARGET')
        require(bg['primary_quality_target'] in bg['expected_behavior_verdicts'], 'B_PRIMARY_TARGET')
        behavior_partition(bg)
        public_secrets += [c['raw_text'], c['case_id'], c['pair_id']]
    for pair in sup['pairs']:
        require(len(pair['members']) == len(set(pair['members'])) == 2, 'PAIR_MEMBERS')
        a, b = [case[i] for i in pair['members']]
        require(a['pair_id'] == b['pair_id'] == pair['pair_id'], 'PAIR_LINK')
        require(a['dimension'] == b['dimension'] == pair['dimension'], 'PAIR_DIMENSION')
        require(bool(pair['changed_decision_factor']), 'PAIR_FACTOR_DECLARATION')
        require({a['oracle']['stage_a']['expected_decision'], b['oracle']['stage_a']['expected_decision']} == {'CLEAR', 'NEEDS_REVIEW'}, 'PAIR_GOLD')
        # This verifies a literal replacement only. Meaning was separately reviewed.
        x, y = a['raw_text'], b['raw_text']
        start = 0
        while start < min(len(x), len(y)) and x[start] == y[start]:
            start += 1
        end = 0
        while end < min(len(x) - start, len(y) - start) and x[-1-end] == y[-1-end]:
            end += 1
        replacement = y[start:len(y)-end if end else len(y)]
        require(x[:start] + replacement + x[len(x)-end if end else len(x):] == y, 'PAIR_LITERAL_REPLACEMENT')
    review = load_asset(root, PACKAGE + 'review-summary.json', 'INDEPENDENT_REVIEW_PASS_FINAL_PRE_EXECUTION')
    require(review['open_P1'] == review['open_P2'] == 0 and review['resolved_draft_P2_groups'] == 4, 'REVIEW_NOT_CLOSED')
    require(review['review_basis_sha256'] == '353f7514a4c1d3869ce304d9187814484741df3947cf4ec261d629f4f228d616', 'REVIEW_BASIS_SHA')
    for entry in review['status_only_seal']:
        value = json.loads((root / entry['path']).read_text())
        semantic = json.dumps({k: v for k, v in value.items() if k != 'status'}, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()
        require(entry['changed_fields'] == ['status'] and entry['semantic_input_gold_identical'], 'NONSTATUS_SEAL_CHANGE')
        require(hashlib.sha256(semantic).hexdigest() == entry['status_excluded_semantic_sha256'], 'REVIEW_SEMANTIC_SHA')
        require(digest(root / entry['path']) == entry['sealed_sha256'], 'SEALED_ASSET_SHA')
    load_asset(root, PACKAGE + 'scoring-contract.json', 'PREREGISTERED_SCORING_DESIGN_NO_MODEL_RESULTS')
    # Aggregate artifacts exclude individual old holdout/new supplemental contents.
    for path in (root / PACKAGE).iterdir():
        if path.is_file() and path.suffix in {'.json', '.md', '.py'}:
            require(not any(s and s in path.read_text() for s in public_secrets), 'PUBLIC_ORACLE_CONTENT_LEAK')
    return {'status': 'PASS_STRUCTURAL_ONLY_WITH_SEPARATE_SEMANTIC_REVIEW',
            'original_split_counts': old_counts, 'supplemental_cases': 16,
            'supplemental_pairs': 8, 'unique_case_ids': len(ids),
            'source_primary_gold_and_full_A_provenance_unchanged': True,
            'DRAFT_inputs_rejected': True, 'final_SHA_pins_verified': True,
            'semantic_pairs': 'PASS_SEPARATE_INDEPENDENT_REVIEW_NOT_REGEX_OR_LITERAL_PROOF',
            'model_calls': 0, 'http_calls': 0, 'model_accuracy_measured': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument('--receipt', type=Path)
    args = parser.parse_args()
    try:
        result = validate(args.root)
    except (ValueError, KeyError, OSError, TypeError) as exc:
        # Validation labels and filenames only, never raw case contents.
        print(json.dumps({'status': 'FAIL_STRUCTURAL', 'reason': str(exc)}))
        return 2
    text = json.dumps(result, indent=2) + '\n'
    if args.receipt:
        args.receipt.write_text(text)
    print(text, end='')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
