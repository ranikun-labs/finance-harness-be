#!/usr/bin/env bash
# Regression test for the read-token gate. Runs the real gate_decide from probe-gate.sh (no mocks).
# Tokenless: it needs no secret, so it runs in the unprotected job. Prints GATE_TEST=pass|fail, exits 1 on fail.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=probe-gate.sh
source "$here/probe-gate.sh"

RUN=1111111111111111111111111111111111111111
OTHER=2222222222222222222222222222222222222222
PIN=3333333333333333333333333333333333333333

field() { printf '%s\n' "$1" | sed -n "s/^$2=//p"; }
fails=0
check() { # name, actual, expected
  if [ "$2" = "$3" ]; then
    echo "  ok   $1: $2"
  else
    echo "  FAIL $1: got '$2' expected '$3'"; fails=$((fails+1))
  fi
}

run_case() { # name tiu approved expect_token expect_checkout expect_transition
  local out; out="$(gate_decide "$2" "$3" "$RUN" "$PIN")"
  echo "case: $1"
  check "token_use" "$(field "$out" token_use)" "$4"
  check "checkout_go" "$(field "$out" checkout_go)" "$5"
  check "transition_eligible" "$(field "$out" transition_eligible)" "$6"
}

# (token_in_unprotected, approved_sha)
run_case "yes + mismatch: download and checkout BLOCKED, transition blocked" yes "$OTHER" blocked no no
run_case "yes + match: checkout allowed, transition blocked (boundary not protected)" yes "$RUN" allowed yes no
run_case "no + match: checkout allowed, transition eligible" no "$RUN" allowed yes yes
run_case "no + mismatch: download and checkout BLOCKED, transition blocked" no "$OTHER" blocked no no
run_case "missing approved sha: all token steps BLOCKED" no "" blocked no no
run_case "missing approved sha with unprotected yes: all BLOCKED" yes "" blocked no no

# Pin validity is independent of approval: a malformed pin never runs the checkout.
out="$(gate_decide no "$RUN" "$RUN" "not-a-sha")"
echo "case: approved + malformed pin"
check "checkout_go" "$(field "$out" checkout_go)" "no"

if [ "$fails" -eq 0 ]; then
  echo "GATE_TEST=pass"
  exit 0
fi
echo "GATE_TEST=fail ($fails assertion(s))"
exit 1
