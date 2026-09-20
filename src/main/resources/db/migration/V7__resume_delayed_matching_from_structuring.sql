-- Kakao Local 기반 MATCHING 단계가 제출 MVP에서 제거되었다.
-- 배포 전에 해당 단계에서 지연된 고정 배치는 새 Gemini 장소 그룹 계약으로 다시 구조화한다.
UPDATE coordination_runs
SET resume_stage = 'STRUCTURING'
WHERE status = 'ANALYSIS_DELAYED'
  AND resume_stage = 'MATCHING';
