#!/usr/bin/env bash
# Gate logic for the read-token path. Shared by the workflow and by test_probe_gate.sh, so the test covers the
# real decision code. Contains no secret values and never prints a token.
#
# Three independent inputs:
#   token_in_unprotected  yes|no   from the tokenless job (is the repo/org secret also reachable there?)
#   approved_sha          vars.APPROVED_PROBE_SHA (set by Owner in the dev-private-read environment)
#   run_sha               github.sha of this run
# approved_sha_match gates EVERY token-using step. boundary (token exposure) gates only the transition verdict.
# The candidate branch can edit this file. The enforcing controls are the environment, its variables, and the
# protected branch, all of which live outside the candidate.

# gate_approved_match APPROVED_SHA RUN_SHA -> true|false
gate_approved_match() {
  if [ -n "${1:-}" ] && [ "$1" = "${2:-}" ]; then echo true; else echo false; fi
}

# gate_boundary TOKEN_IN_UNPROTECTED -> protected | not_protected (...)
gate_boundary() {
  case "${1:-}" in
    no) echo protected ;;
    yes) echo "not_protected (org/repo secret also provided)" ;;
    *) echo "not_protected (unprotected-job check missing)" ;;
  esac
}

# gate_is_pin PIN -> true|false  (40 lowercase hex)
gate_is_pin() {
  if [[ "${1:-}" =~ ^[0-9a-f]{40}$ ]]; then echo true; else echo false; fi
}

# gate_decide TOKEN_IN_UNPROTECTED APPROVED_SHA RUN_SHA PIN -> key=value lines
#   approved_sha_match  true|false
#   boundary            protected | not_protected (...)
#   token_use           allowed | blocked            (authenticated download, token-using steps)
#   checkout_go         yes | no                     (PROTECTED_CHECKOUT; needs approval and a valid pin)
#   transition_eligible yes | no                     (needs approval AND boundary protected; private checks still follow)
#   transition_block_reason  reason when transition_eligible=no
gate_decide() {
  local tiu="$1" approved="$2" run="$3" pin="$4" am boundary token_use checkout_go eligible reason
  am="$(gate_approved_match "$approved" "$run")"
  boundary="$(gate_boundary "$tiu")"
  if [ "$am" = true ]; then token_use=allowed; else token_use=blocked; fi
  if [ "$am" = true ] && [ "$(gate_is_pin "$pin")" = true ]; then checkout_go=yes; else checkout_go=no; fi
  reason=""
  if [ "$am" != true ]; then
    reason=approved_sha_mismatch_or_missing
  elif [ "$boundary" != protected ]; then
    reason="boundary:${boundary%% *}"
  fi
  if [ -z "$reason" ]; then eligible=yes; else eligible=no; fi
  printf 'approved_sha_match=%s\nboundary=%s\ntoken_use=%s\ncheckout_go=%s\ntransition_eligible=%s\ntransition_block_reason=%s\n' \
    "$am" "$boundary" "$token_use" "$checkout_go" "$eligible" "$reason"
}
