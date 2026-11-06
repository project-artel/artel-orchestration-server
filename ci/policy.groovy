// Pure decisions shared by CI, trusted CD and the controller-side contract tests.
def reuse(boolean isPr, boolean force, String result, String key, String previousKey) {
    isPr && !force && result == 'SUCCESS' && key && key == previousKey
}

def environmentFor(String job) {
    def environments = [
        'artel-orchestration-server/develop': 'stage',
        'artel-orchestration-server/stage': 'stage',
        'artel-orchestration-server/main': 'operation',
        'artel-orchestration-server/operation': 'operation',
    ]
    environments[job]
}

def validSource(String job, int number, String causeJob, int causeNumber, String result) {
    environmentFor(job) != null && number > 0 && job == causeJob && number == causeNumber && result == 'SUCCESS'
}

def stale(long sourceTime, long latestSuccessfulTime) { sourceTime < latestSuccessfulTime }

return this
