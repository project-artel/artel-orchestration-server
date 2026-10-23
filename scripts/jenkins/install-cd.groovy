// Run as administrator with bindings pipelineText, policyText, deployText.
// Creates a disabled trusted job. Refuses to overwrite an existing job without explicit replaceExisting=true.
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

def name = 'artel-orchestration-deploy'
def jenkins = Jenkins.get()
def job = jenkins.getItem(name)
if (job != null && !(binding.hasVariable('replaceExisting') && replaceExisting)) {
    throw new IllegalStateException('Existing CD job requires explicit replaceExisting=true')
}
if (job != null && job.isBuilding()) throw new IllegalStateException('Do not replace an active deployment')
def text = pipelineText.replace('__POLICY_BASE64__', policyText.getBytes('UTF-8').encodeBase64().toString())
                       .replace('__DEPLOY_BASE64__', deployText.getBytes('UTF-8').encodeBase64().toString())
if (job == null) job = jenkins.createProject(WorkflowJob, name)
job.setDisabled(true)
job.setDefinition(new CpsFlowDefinition(text, false))
job.setDescription('ARTEL-1165: trusted serial CD. Enable only after reviewed CI rollout and legacy deployment drain.')
job.save()
println('Prepared disabled job: ' + name)
