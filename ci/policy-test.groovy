// Run with binding policy pointing to the evaluated ci/policy.groovy.
assert policy.reuse(true, false, 'SUCCESS', 'head:base:v1', 'head:base:v1')
assert !policy.reuse(true, false, 'SUCCESS', 'head:base:v2', 'head:base:v1')
assert !policy.reuse(true, false, 'SUCCESS', 'head:base2:v1', 'head:base1:v1')
assert !policy.reuse(true, true, 'SUCCESS', 'same', 'same')
assert !policy.reuse(false, false, 'SUCCESS', 'same', 'same')
assert !policy.reuse(true, false, 'UNSTABLE', 'same', 'same')
assert !policy.reuse(true, false, 'SUCCESS', 'same', null)
assert policy.validSource('artel-orchestration-server/develop', 4, 'artel-orchestration-server/develop', 4, 'SUCCESS')
assert !policy.validSource('artel-orchestration-server/PR-344', 4, 'artel-orchestration-server/PR-344', 4, 'SUCCESS')
assert !policy.validSource('artel-orchestration-server/develop', 4, 'artel-orchestration-server/PR-344', 4, 'SUCCESS')
assert !policy.validSource('artel-orchestration-server/develop', 4, 'artel-orchestration-server/develop', 3, 'SUCCESS')
assert !policy.validSource('artel-orchestration-server/develop', 4, 'artel-orchestration-server/develop', 4, 'UNSTABLE')
assert policy.stale(100L, 101L)
assert !policy.stale(101L, 101L)
assert policy.environmentFor('artel-orchestration-server/stage') == 'stage'
assert policy.environmentFor('artel-orchestration-server/main') == 'operation'
println('16 policy contract assertions passed')
