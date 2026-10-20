#!/usr/bin/env bash
set -euo pipefail
set +x

destination="${1:-/opt/meet-me/current/.env.runtime}"
parameter_root="${MEETME_PARAMETER_ROOT:-/meet-me/production}"
region="${AWS_REGION:-ap-northeast-2}"

runtime_fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

if [[ -L "$destination" || ( -e "$destination" && ! -f "$destination" ) ]]; then
  runtime_fail 'Runtime environment destination is unsafe'
fi

# A stored legacy release without this declaration remains Gemini-only.
# Resolve the declaration from this renderer's release, including on DB refresh.
mode_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/runtime-provider-mode"
runtime_mode=gemini-only
if [[ -L "$mode_file" ]]; then
  runtime_fail 'Runtime provider mode is invalid'
elif [[ -e "$mode_file" ]]; then
  [[ -f "$mode_file" && -r "$mode_file" ]] || runtime_fail 'Runtime provider mode is invalid'
  mode_permissions="$(stat -c '%a' "$mode_file" 2>/dev/null)" ||
    runtime_fail 'Runtime provider mode is invalid'
  [[ "$mode_permissions" =~ ^[0-7]{3,4}$ ]] ||
    runtime_fail 'Runtime provider mode is invalid'
  (( (8#$mode_permissions & 0444) != 0 )) ||
    runtime_fail 'Runtime provider mode is invalid'
  # Validate raw bytes before Bash could trim newlines or discard NUL.
  if ! runtime_mode="$(jq -Rser '
    select(. == "gemini-only" or . == "gemini-only\n" or
      . == "gemini-luna-required" or . == "gemini-luna-required\n") |
    rtrimstr("\n")
  ' "$mode_file" 2>/dev/null)"; then
    runtime_fail 'Runtime provider mode is invalid'
  fi
fi

release_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
auth_mode_file="$release_directory/runtime-auth-mode"
auth_required=false
if [[ -L "$auth_mode_file" ]]; then
  runtime_fail 'Runtime authentication mode is invalid'
elif [[ -e "$auth_mode_file" ]]; then
  [[ -f "$auth_mode_file" && -r "$auth_mode_file" ]] || runtime_fail 'Runtime authentication mode is invalid'
  auth_mode_permissions="$(stat -c '%a' "$auth_mode_file" 2>/dev/null)" ||
    runtime_fail 'Runtime authentication mode is invalid'
  [[ "$auth_mode_permissions" =~ ^[0-7]{3,4}$ ]] || runtime_fail 'Runtime authentication mode is invalid'
  (( (8#$auth_mode_permissions & 0444) != 0 )) || runtime_fail 'Runtime authentication mode is invalid'
  if ! jq -Rse 'select(. == "oauth-rs256-required" or . == "oauth-rs256-required\n")' \
    "$auth_mode_file" >/dev/null 2>&1; then
    runtime_fail 'Runtime authentication mode is invalid'
  fi
  auth_required=true
  # New releases default to their own canonical directory; legacy defaults stay unchanged.
  if [[ "$#" == 0 ]]; then destination="$release_directory/.env.runtime"; fi
fi

validate_auth_destination() {
  local entry="$destination"
  [[ ! -L "$entry" && ( ! -e "$entry" || -f "$entry" ) ]] ||
    runtime_fail 'Runtime environment destination is unsafe'
  while [[ "$entry" != / && "$entry" != . ]]; do
    [[ ! -L "$entry" ]] || runtime_fail 'Runtime environment destination is unsafe'
    entry="$(dirname -- "$entry")"
  done
}
if [[ "$auth_required" == true ]]; then validate_auth_destination; fi

openai_api_key=
typesafe_api_key=
if [[ "$runtime_mode" == gemini-luna-required ]]; then
  if ! openai_parameter="$(aws ssm get-parameter \
    --region "$region" \
    --name "$parameter_root/secret/openai-api-key" \
    --with-decryption \
    --output json 2>/dev/null)"; then
    runtime_fail 'Required Luna runtime parameter is unavailable'
  fi
  # Validate JSON before command substitution can trim newlines or discard NUL.
  if ! openai_api_key="$(jq -ser '
    select(length == 1) | .[0].Parameter |
    select(.Type == "SecureString") | .Value |
    select(type == "string" and length > 0) |
    select(explode | all(. >= 33 and . <= 126))
  ' <<< "$openai_parameter" 2>/dev/null)"; then
    runtime_fail 'Required Luna runtime parameter is invalid'
  fi
  unset openai_parameter
fi

get_parameter() {
  aws ssm get-parameter \
    --region "$region" \
    --name "$1" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
}

get_auth_parameter() {
  # Never capture unvalidated JSON in Bash: command substitution discards raw NUL.
  if ! aws ssm get-parameter \
    --region "$region" --name "$parameter_root/$1" \
    --with-decryption --output json 2>/dev/null |
    jq -Rser --arg expected_type "$2" '
    select(explode | all(. != 0)) | fromjson | .Parameter |
    select(.Type == $expected_type) | .Value |
    select(type == "string" and length > 0) |
    select(explode | all(. >= 33 and . <= 126))
  ' 2>/dev/null; then
    runtime_fail 'Required authentication runtime parameter is invalid'
  fi
}

if [[ "$auth_required" == true ]]; then
  google_client_id="$(get_auth_parameter secret/google-client-id SecureString)"
  google_client_secret="$(get_auth_parameter secret/google-client-secret SecureString)"
  kakao_client_id="$(get_auth_parameter secret/kakao-client-id SecureString)"
  kakao_client_secret="$(get_auth_parameter secret/kakao-client-secret SecureString)"
  google_redirect_uri="$(get_auth_parameter config/google-redirect-uri String)"
  kakao_redirect_uri="$(get_auth_parameter config/kakao-redirect-uri String)"
  auth_signing_key_id="$(get_auth_parameter config/auth-signing-key-id String)"
  auth_issuer="$(get_auth_parameter config/auth-issuer String)"
  auth_audience="$(get_auth_parameter config/auth-audience String)"
  auth_web_callback_uri="$(get_auth_parameter config/auth-web-callback-uri String)"
  allowed_origins="$(get_auth_parameter config/allowed-origins String)"
  precheck_provider_enabled=false
  if precheck_parameter="$(
    set +e
    aws ssm get-parameter \
      --region "$region" --name "$parameter_root/config/precheck-provider-enabled" \
      --with-decryption --output json 2>/dev/null |
      jq -Rser '
      select(explode | all(. != 0)) | fromjson | .Parameter |
      select(.Type == "String") | .Value |
      select(type == "string" and (. == "true" or . == "false"))
    ' 2>/dev/null
    pipeline_status=("${PIPESTATUS[@]}")
    if [[ "${pipeline_status[0]}" != 0 ]]; then exit 71; fi
    if [[ "${pipeline_status[1]}" != 0 ]]; then exit 72; fi
  )"; then
    precheck_provider_enabled="$precheck_parameter"
  else
    lookup_status="$?"
    if [[ "$lookup_status" != 71 ]]; then
      runtime_fail 'Pre-submit provider runtime configuration is invalid'
    fi
  fi
  unset precheck_parameter

  # This release's reviewed, nonsecret artifact is the only policy source.
  # Validate before replacing the raw environment; no SSM value can supply financial authority.
  if ! policy_info="$(python3 -I - "$release_directory/runtime-ai-policy.json" "$precheck_provider_enabled" <<'PY'
import datetime,decimal,hashlib,json,os,re,stat,sys
try:
    path,enabled=sys.argv[1:]
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    with os.fdopen(fd,'rb') as stream:
        metadata=os.fstat(stream.fileno())
        assert stat.S_ISREG(metadata.st_mode) and metadata.st_size<=65536 and metadata.st_mode&0o444
        raw=stream.read(65537)
    assert len(raw)<=65536
    def unique(pairs):
        result={}
        for key,value in pairs:
            assert key not in result
            result[key]=value
        return result
    def invalid_constant(value):raise ValueError()
    data=json.loads(raw.decode('utf-8'),object_pairs_hook=unique,parse_float=decimal.Decimal,parse_constant=invalid_constant)
    assert type(data) is dict and data.get('protocol')=='meetme-managed-ai-policy-v1'
    threshold=''
    if data.get('state')=='UNCONFIGURED':
        assert set(data)=={'protocol','state'} and enabled=='false'
    else:
        assert data.get('state')=='CONFIGURED' and set(data)=={'protocol','state','unit','roomCap','minimumConfidence','operations'}
        unit=data['unit']
        assert type(unit) is dict and set(unit)=={'code','scale'}
        code=unit['code']
        assert type(code) is str and code and code==code.strip() and len(code.encode('utf-16-le'))//2<=32
        assert not any(value in code for value in ('\x00','\r','\n')) and type(unit['scale']) is int and 0<=unit['scale']<=38
        def amount(value):return type(value) is str and re.fullmatch(r'0|[1-9][0-9]{0,37}',value)
        assert amount(data['roomCap']) and int(data['roomCap'])>0
        confidence=data['minimumConfidence']
        assert type(confidence) in (int,decimal.Decimal) and decimal.Decimal(confidence).is_finite() and 0<=confidence<=1
        profiles={
            'PRECHECK_CLASSIFICATION':'c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc',
            'PRECHECK_COUNT':'658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307',
            'PRECHECK_GENERATION':'b9ce9802d9dc3f4396f49c796cfe173fda708c6a35ac40c79d5bdc5091419363',
            'FINAL_PRIMARY':'0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04',
            'FINAL_FALLBACK':'9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab'}
        operations=data['operations']
        assert type(operations) is list and len(operations)==5
        seen=set()
        for row in operations:
            assert type(row) is dict and set(row)=={'operation','profileFingerprint','evidenceFingerprint','maximumAmount','validUntil'}
            operation=row['operation']
            assert operation in profiles and operation not in seen and row['profileFingerprint']==profiles[operation]
            seen.add(operation)
            assert type(row['evidenceFingerprint']) is str and re.fullmatch('[0-9a-f]{64}',row['evidenceFingerprint'])
            assert amount(row['maximumAmount']) and type(row['validUntil']) is str and row['validUntil'].endswith('Z')
            expiry=datetime.datetime.fromisoformat(row['validUntil'].replace('Z','+00:00'))
            assert expiry.utcoffset()==datetime.timedelta(0)
            if enabled=='true':assert expiry>datetime.datetime.now(datetime.timezone.utc)
        if enabled=='true':threshold=str(confidence)
    print(hashlib.sha256(raw).hexdigest()+':'+threshold)
except Exception:
    sys.exit(2)
PY
  )"; then
    runtime_fail 'Managed AI runtime policy is invalid or unavailable'
  fi
  policy_sha256="${policy_info%%:*}"
  classifier_minimum_confidence="${policy_info#*:}"
  unset policy_info
  if [[ "$precheck_provider_enabled" == true ]]; then
    typesafe_api_key="$(get_auth_parameter secret/typesafe-api-key SecureString)"
  fi
fi

database_secret_arn="$(get_parameter "$parameter_root/config/database-secret-arn")"
database_secret="$(aws secretsmanager get-secret-value \
  --region "$region" \
  --secret-id "$database_secret_arn" \
  --query 'SecretString' \
  --output text)"
database_password="$(printf '%s' "$database_secret" | jq -er '.password')"

database_url="$(get_parameter "$parameter_root/config/database-url")"
database_username="$(get_parameter "$parameter_root/config/database-username")"
redis_host="$(get_parameter "$parameter_root/config/redis-host")"
redis_port="$(get_parameter "$parameter_root/config/redis-port")"
redis_ssl_enabled="$(get_parameter "$parameter_root/config/redis-ssl-enabled")"
if [[ "$auth_required" != true ]]; then
  allowed_origins="$(get_parameter "$parameter_root/config/allowed-origins")"
fi
gemini_api_key="$(get_parameter "$parameter_root/secret/gemini-api-key")"

umask 077
temporary="$(mktemp "${destination}.XXXXXX")"
trap 'rm -f "$temporary"' EXIT

{
  printf 'DATABASE_URL=%s\n' "$database_url"
  printf 'DATABASE_USERNAME=%s\n' "$database_username"
  printf 'DATABASE_PASSWORD=%s\n' "$database_password"
  printf 'DATABASE_MAX_POOL_SIZE=10\n'
  printf 'REDIS_HOST=%s\n' "$redis_host"
  printf 'REDIS_PORT=%s\n' "$redis_port"
  printf 'REDIS_SSL_ENABLED=%s\n' "$redis_ssl_enabled"
  printf 'GEMINI_API_KEY=%s\n' "$gemini_api_key"
  printf 'MEETME_RUNTIME_PROVIDER_MODE=%s\n' "$runtime_mode"
  if [[ "$runtime_mode" == gemini-luna-required ]]; then
    printf 'OPENAI_API_KEY=%s\n' "$openai_api_key"
  fi
  printf 'APP_ALLOWED_ORIGINS=%s\n' "$allowed_origins"
  if [[ "$auth_required" == true ]]; then
    printf 'GOOGLE_CLIENT_ID=%s\n' "$google_client_id"
    printf 'GOOGLE_CLIENT_SECRET=%s\n' "$google_client_secret"
    printf 'GOOGLE_REDIRECT_URI=%s\n' "$google_redirect_uri"
    printf 'KAKAO_CLIENT_ID=%s\n' "$kakao_client_id"
    printf 'KAKAO_CLIENT_SECRET=%s\n' "$kakao_client_secret"
    printf 'KAKAO_REDIRECT_URI=%s\n' "$kakao_redirect_uri"
    printf 'AUTH_SIGNING_KEY_ID=%s\n' "$auth_signing_key_id"
    printf 'AUTH_ISSUER=%s\n' "$auth_issuer"
    printf 'AUTH_AUDIENCE=%s\n' "$auth_audience"
    printf 'AUTH_WEB_CALLBACK_URI=%s\n' "$auth_web_callback_uri"
    printf 'AUTH_SIGNING_PRIVATE_KEY_PATH=/run/meetme-auth/signing-private.pem\n'
    printf 'AUTH_VERIFICATION_JWKS_PATH=/run/meetme-auth/verification.jwks.json\n'
    printf 'MEETME_PRECHECK_PROVIDER_ENABLED=%s\n' "$precheck_provider_enabled"
    printf 'MEETME_PRECHECK_DECISIONROUTING_ENABLED=%s\n' "$precheck_provider_enabled"
    printf 'MEETME_PRECHECK_CLASSIFIER_ENABLED=%s\n' "$precheck_provider_enabled"
    printf 'MEETME_AI_BUDGET_ENABLED=%s\n' "$precheck_provider_enabled"
    printf 'MEETME_AI_BUDGET_POLICYPATH=/run/meetme-ai/runtime-ai-policy.json\n'
    printf 'MEETME_AI_BUDGET_POLICYSHA256=%s\n' "$policy_sha256"
    if [[ "$precheck_provider_enabled" == true ]]; then
      printf 'MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE=%s\n' "$classifier_minimum_confidence"
      printf 'TYPESAFE_API_KEY=%s\n' "$typesafe_api_key"
    fi
  fi
  printf 'ANONYMOUS_COOKIE_SECURE=true\n'
  printf 'MANAGEMENT_PORT=9090\n'
  printf 'MANAGEMENT_ADDRESS=127.0.0.1\n'
  printf 'REDIS_CONSUMER_NAME=meet-me-production\n'
} >"$temporary"

chmod 0600 "$temporary"
if [[ "$auth_required" == true ]]; then validate_auth_destination; fi
mv -T "$temporary" "$destination"
