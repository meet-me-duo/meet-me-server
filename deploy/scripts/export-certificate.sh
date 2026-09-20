#!/usr/bin/env bash
set -euo pipefail
set +x

destination="${1:-/opt/meet-me/tls}"
parameter_root="${MEETME_PARAMETER_ROOT:-/meet-me/production}"
region="${AWS_REGION:-ap-northeast-2}"

get_parameter() {
  aws ssm get-parameter \
    --region "$region" \
    --name "$1" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
}

certificate_arn="$(get_parameter "$parameter_root/config/certificate-arn")"
passphrase="$(get_parameter "$parameter_root/secret/acm-export-passphrase")"

umask 077
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

printf '%s' "$passphrase" >"$workdir/passphrase"
aws acm export-certificate \
  --region "$region" \
  --certificate-arn "$certificate_arn" \
  --passphrase "fileb://$workdir/passphrase" \
  >"$workdir/certificate.json"

jq -er '.Certificate' "$workdir/certificate.json" >"$workdir/certificate.pem"
jq -er '.CertificateChain' "$workdir/certificate.json" >"$workdir/chain.pem"
jq -er '.PrivateKey' "$workdir/certificate.json" >"$workdir/private-key.pem"
cat "$workdir/certificate.pem" "$workdir/chain.pem" >"$workdir/fullchain.pem"

install -d -m 0750 "$destination"
install -m 0600 "$workdir/passphrase" "$destination/passphrase"
install -m 0600 "$workdir/private-key.pem" "$destination/private-key.pem"
install -m 0644 "$workdir/fullchain.pem" "$destination/fullchain.pem"

