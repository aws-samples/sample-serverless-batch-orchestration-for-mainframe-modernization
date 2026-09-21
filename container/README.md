# Container layer — faithful skeleton + runnable stub

A modernized batch job on AWS is **two AWS Transform for mainframe artifacts**
running on the licensed **refactor runtime** (Apache Tomcat + JICS).
The single container image bakes in every job; the ECS `Command` override selects
one by name.

```
ECS RunTask  Command=["JOB2A"]
      │
      ▼
entrypoint.sh  ──HTTP──▶  GET /gapwalk-application/script/JOB2A   (Gapwalk webapp)
                                     │
                                     ▼
                         JOB2A.jcl.groovy   (JCL → Groovy: S3 download, COND gating,
                                     │        runProgram, S3 upload, return code)
                                     ▼
                         runProgram("JOB2A") ──▶ Job program bean (COBOL → Java)
```

The container exit code = the Groovy `exitCode` = the program return code, which
Step Functions reads via `Containers[N].ExitCode`.

## Layout

```
container/
├── Dockerfile               # faithful — single image, command-override structure
├── entrypoint.sh            # faithful — boots Tomcat, drives the webapp
├── jobs/JOB1.jcl.groovy     # faithful — JCL → Groovy
├── programs/Job1.java       # faithful — COBOL → Java
├── app-config/s3-config.yml # faithful — DD-name → S3-key mapping
└── runnable-stub/           # runnable — Java + AWS SDK test double
```

## `jobs/`, `programs/`, `Dockerfile`, `entrypoint.sh` — FAITHFUL (not runnable)

These mirror exactly what AWS Transform emits and where your logic plugs in:

- **`jobs/JOB1.jcl.groovy`** — the JCL→Groovy job. Selected by the command
  override. Downloads S3 inputs, runs program steps with DD→file bindings, gates on
  condition codes (`checkValidProgramResults` == JCL `COND`), uploads outputs.
- **`programs/Job1.java`** — the COBOL→Java program bean. Preserves the original
  COBOL header; the `// REPLACE` block is the only part you swap.
- **`Dockerfile` / `entrypoint.sh`** — single image; scripts symlinked to
  extensionless names so `["JOB2A"]` maps to a script; entrypoint drives Gapwalk
  over localhost HTTP.

They **do not build/run** without your licensed AWS Transform for mainframe refactor
runtime base image and your transformed WARs. They are here for real-world coherence.

## `runnable-stub/` — RUNNABLE (test double)

A tiny Java + AWS SDK program honoring the **identical contract**
(command override → `args[0]`, reads `S3_BUCKET`/`S3_PREFIX`/`AWS_REGION`, S3
in/out, exit 0/non-zero). It lets the CloudFormation + Step Functions sample run
green in a sandbox so you can see the orchestration work end-to-end.

It is **not** a modernization — it proves the orchestration, not the program logic.
In production, point `iac/20-ecs.yaml`'s `ContainerImage` at your transformed
AWS Transform for mainframe refactor runtime image instead of this stub.

```bash
# build + run one job locally against a bucket
docker build -t batch-stub runnable-stub
docker run --rm -e S3_BUCKET=my-bucket -e AWS_REGION=ap-southeast-2 \
  -e S3_PREFIX=test-runs/demo/ batch-stub JOB1
```
