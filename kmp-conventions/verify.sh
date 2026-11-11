#!/usr/bin/env bash
# Proves the conventions: unit tests, the positive sample passes, the negative fixture fails.
# Uses the repo's gradlew if present, else `gradle` on PATH (8.14.3 tested).
set -u
cd "$(dirname "$0")"
G=gradle; [ -x ../gradlew ] && G=../gradlew
fail=0
$G test --console=plain || fail=1
(cd sample && ../$G verifyNoAndroidImports verifyDependencyLicences --console=plain) || fail=1
for t in verifyNoAndroidImports verifyDependencyLicences; do
  if (cd sample-negative && ../$G $t --console=plain >/dev/null 2>&1); then
    echo "NEGATIVE FIXTURE DID NOT FAIL: $t"; fail=1
  else echo "negative fixture failed $t, as required"; fi
done
exit $fail
