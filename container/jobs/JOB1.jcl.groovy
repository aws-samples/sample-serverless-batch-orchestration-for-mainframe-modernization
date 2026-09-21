// =============================================================================
// JOB1.jcl.groovy  —  FAITHFUL REFERENCE SKELETON (not runnable standalone)
//
// This is the shape AWS Transform for mainframe produces when it converts a JCL
// job to Groovy for the AWS Blu Age / Gapwalk runtime. The ECS Command override
// (["JOB1"]) selects this script; entrypoint.sh invokes it through the Gapwalk
// webapp: GET /gapwalk-application/script/JOB1.
//
// It is NOT runnable outside your licensed Blu Age/Gapwalk runtime — the
// com.netfective.bluage.* imports and the S3FileHandler resolve there. Use it to
// understand the two-layer contract; use container/runnable-stub for a container
// that actually deploys and runs in a sandbox.
//
// Responsibilities of the JCL/Groovy layer (job control — reused verbatim):
//   1. Download this job's input files from S3 -> local temp (S3FileHandler).
//   2. Run each program step, binding DDs to files (FileConfigurationUtils).
//   3. Gate steps on prior condition codes (checkValidProgramResults == JCL COND).
//   4. Upload output files back to S3.
//   5. Return a GroovyExecutionResult whose return code becomes the exit code.
//
// The business logic lives in the *program* (JOB1 -> programs/Job1.java), invoked
// below via runProgram("JOB1"). That is the COBOL->Java layer you replace.
// =============================================================================

import com.netfective.bluage.gapwalk.rt.provider.ScriptRegistry
import com.netfective.bluage.gapwalk.rt.call.MainProgramRunner
import com.netfective.bluage.gapwalk.io.support.FileConfigurationUtils
import com.netfective.bluage.gapwalk.rt.call.exception.GroovyExecutionException
import com.netfective.bluage.gapwalk.rt.call.exception.GroovyExecutionResult

mpr = applicationContext.getBean("com.netfective.bluage.gapwalk.rt.call.ExecutionController", MainProgramRunner.class)
Map params = ["MapTransfo": [:] as TreeMap]

// --- S3 integration (per-execution isolation via S3_PREFIX / testRunPrefix) ---
def workingDirectory = System.getProperty("job.working.dir") ?: System.getProperty("user.dir") ?: "."
def s3ConfigPath = "${workingDirectory}/config/s3-config.yml"
def s3Handler = null
try {
    def cls = new GroovyClassLoader(this.class.classLoader)
        .parseClass(new File("${workingDirectory}/utils/S3FileHandler.groovy").text)
    s3Handler = cls.newInstance(s3ConfigPath)          // reads S3_BUCKET, S3_PREFIX, AWS_REGION from env
    s3Handler.downloadInputFilesForJob("JOB1")          // inbound/transactions/ -> local temp
} catch (Exception e) {
    println "Warning: S3 handler init failed (${e.message}); falling back to local files"
}

// --- Job body (mirrors the mainframe JOB card + steps) -----------------------
Binding binding = new Binding()
binding.setVariable("jobContext", jobContext)
def shell = new GroovyShell(binding).parse(ScriptRegistry.getScript("functions"))

shell.with {
    def jobName = "JOB1"
    mpr.setJobContext(jobContext)
    displayStartJob(jobName)
    Map programResults = jobContext.getProgramResults().clone()

    try {
        stepJOB1(shell, params, programResults, s3Handler, workingDirectory)

        if (s3Handler != null) {
            // processing/JOB1/output/  <- local temp
            s3Handler.uploadAllOutputFiles(s3Handler.getTempDirectory().toString(), "JOB1")
        }
    } finally {
        if (s3Handler != null) { try { s3Handler.cleanup(); s3Handler.close() } catch (ignored) {} }
    }

    displayEndJob(jobName)
    return programResults.get("GroovyExecutionResult")   // return code -> container exit code
}

// --- STEP JOB1 — PGM JOB1 -----------------------------------------------------
def stepJOB1(Object shell, Map params, Map programResults, def s3Handler, String workingDirectory) {
    shell.with {
        if (checkValidProgramResults(programResults)) {          // JCL COND gate
            return execStep("JOB1", "JOB1", programResults, {
                def tempDir = s3Handler?.getTempDirectory()?.toString() ?: workingDirectory
                mpr
                    .withFileConfigurations(new FileConfigurationUtils()
                        .withJobContext(jobContext)
                        // Input DD  (== inbound/transactions/txns.YYYYMMDD.csv)
                        .fileSystem("TXNIN").path("${tempDir}/txns.input").disposition("SHR").recordSize(80).build()
                        // Output DD (== processing/JOB1/output/normalized.csv)
                        .fileSystem("NORMOUT").path("${tempDir}/normalized.csv").disposition("NEW").normalTermination("CATLG").recordSize(80).build()
                        .systemOut("SYSPRINT").output("*").build()
                        .getFileConfigurations())
                    .withParameters(params)
                    .runProgram("JOB1")                          // -> programs/Job1.java
            })
        }
    }
}
