"""PROTOCOL_ONLY: invented records exercise scoring arithmetic, never model outcomes."""
import collections,json,pathlib
B=pathlib.Path(__file__).parent
rows=[
 dict(id='P1',gold='NEEDS_REVIEW',status='VALID_CHOICE',raw='CLEAR',reason='LOW_CONFIDENCE',b='NEEDS_CHOICE'),
 dict(id='P2',gold='CLEAR',status='VALID_CHOICE',raw='NEEDS_REVIEW',reason=None,b='NEEDS_CHOICE'),
 dict(id='P3',gold='NEEDS_REVIEW',status='VALID_CHOICE',raw='INDETERMINATE',reason='MODEL_UNCERTAIN',b='NEEDS_EDIT'),
 dict(id='P4',gold='NEEDS_REVIEW',status='HTTP_ERROR',raw=None,reason=None,b=None),
 dict(id='P5',gold='NEEDS_REVIEW',status='UNRUN',raw=None,reason=None,b=None),
 dict(id='P6',gold='INDETERMINATE',status='REFUSAL',raw=None,reason='REFUSED',b=None),
 dict(id='P7',gold='INDETERMINATE',status='VALID_CHOICE',raw='INDETERMINATE',reason='MODEL_UNCERTAIN',b='NEEDS_EDIT'),
 dict(id='P8',gold='CLEAR',status='VALID_CHOICE',raw='CLEAR',reason=None,b=None),
 dict(id='P9',gold='NEEDS_REVIEW',status='VALID_CHOICE',raw='CLEAR',reason=None,b=None)
]
def score(rs):
 return dict(planned=len(rs),gold_counts=dict(collections.Counter(r['gold'] for r in rs)),status_counts=dict(collections.Counter(r['status'] for r in rs)),valid_choices=sum(r['status']=='VALID_CHOICE' for r in rs),raw_material_false_negatives=sum(r['gold']=='NEEDS_REVIEW' and r['raw']=='CLEAR' for r in rs),raw_material_planned_denominator=sum(r['gold']=='NEEDS_REVIEW' for r in rs),raw_material_valid_denominator=sum(r['gold']=='NEEDS_REVIEW' and r['status']=='VALID_CHOICE' for r in rs),unnecessary_questions=sum(r['gold']=='CLEAR' and r['b']=='NEEDS_CHOICE' for r in rs),clear_planned_denominator=sum(r['gold']=='CLEAR' for r in rs),clear_B_attempt_denominator=sum(r['gold']=='CLEAR' and r['b'] is not None for r in rs),safe_but_incomplete=sum(r['gold']=='NEEDS_REVIEW' and r['b']=='NEEDS_EDIT' for r in rs),safe_abstention_target_met=sum(r['gold']=='INDETERMINATE' and r['b']=='NEEDS_EDIT' for r in rs),final_false_clear=sum(r['gold']=='NEEDS_REVIEW' and r['raw']=='CLEAR' and r['reason'] is None and r['b'] is None for r in rs),low_confidence_count=sum(r['reason']=='LOW_CONFIDENCE' for r in rs),model_uncertain_count=sum(r['reason']=='MODEL_UNCERTAIN' for r in rs),refusal_count=sum(r['status']=='REFUSAL' for r in rs),technical_error_count=sum(r['status']=='HTTP_ERROR' for r in rs),unrun_count=sum(r['status']=='UNRUN' for r in rs))
s=score(rows)
assert s['planned']==9 and s['valid_choices']==6
assert s['raw_material_false_negatives']==2 and s['raw_material_planned_denominator']==5 and s['raw_material_valid_denominator']==3
assert s['unnecessary_questions']==1 and s['clear_planned_denominator']==2 and s['clear_B_attempt_denominator']==1
assert s['safe_but_incomplete']==s['safe_abstention_target_met']==s['final_false_clear']==1
assert s['low_confidence_count']==1 and s['model_uncertain_count']==2
assert s['refusal_count']==s['technical_error_count']==s['unrun_count']==1
# Recovery of P1 does not reduce A raw false-negative count. No observed B is N/A, not perfect quality.
def rate(n,d):return None if d==0 else n/d
assert rate(0,0) is None
out=dict(status='PASS_PROTOCOL_ONLY',records=rows,synthetic_arithmetic=s,zero_denominator='N/A',model_execution_count=0,model_accuracy_claim=False,provenance='Entirely invented scoring protocol records, not corpus cases or provider predictions.')
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt',type=pathlib.Path)
args=parser.parse_args()
if args.receipt: args.receipt.write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps(out,indent=2))
print('PASS PROTOCOL_ONLY: false positive, recovered/unrecovered false negative, safe abstention, refusal, error, unrun and N/A denominators remain distinct.')
