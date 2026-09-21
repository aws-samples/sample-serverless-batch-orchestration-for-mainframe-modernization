# Security Policy

## Reporting a Vulnerability

If you discover a potential security issue in this project, we ask that you
notify AWS/Amazon Security via our
[vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/)
or directly via email to [aws-security@amazon.com](mailto:aws-security@amazon.com).

Please do **not** create a public GitHub issue for security vulnerabilities.

We prefer all communications to be in English.

## Scope and Intent

This repository is **sample / reference code** that illustrates an architectural
approach for replacing a mainframe batch scheduler with AWS Step Functions,
Lambda, and ECS Fargate. It is intended to be deployed into a **non-production
(sandbox) account** only. It is **not** production-ready and carries no warranty.
Review it against your own security, compliance, and cost controls before any
real use.

## AWS Services Used

The reference deploys and exercises the following services:

- **AWS Step Functions** — orchestration state machines (main + regression).
- **AWS Lambda** — pre-condition checks (file-arrival and business-day gates).
- **Amazon ECS on AWS Fargate** — batch job execution.
- **Amazon S3** — inter-job data layer.
- **Amazon EventBridge Scheduler** — time-based triggering.
- **Amazon VPC** — private subnets, S3 gateway endpoint, ECR/Logs interface
  endpoints.
- **AWS IAM** — least-privilege roles, one per service.
- **Amazon SNS + Amazon CloudWatch** — alerting and observability.
- **Amazon ECR** — container image registry.

## Security Posture (what the sample already does)

- **S3**: default encryption (SSE-S3 + bucket keys), all public access blocked,
  versioning enabled, and a bucket policy that **denies non-TLS** access.
- **Networking**: tasks run in private subnets with **no NAT/internet path**;
  egress is scoped to the ECR/Logs interface endpoints and the S3 managed prefix
  list (no `0.0.0.0/0`). S3 is reached over a gateway endpoint.
- **IAM**: one least-privilege role per service; S3 access scoped to the single
  data bucket; `ecs:RunTask`/`StopTask`/`DescribeTasks` scoped to this
  environment's task-definition and task ARNs; Step Functions
  `lambda:InvokeFunction` scoped to `batch-*` functions in this account.
- **SNS**: encrypted at rest with the AWS-managed key.
- **CI/CD**: GitHub Actions authenticates via **OIDC** — no long-lived AWS keys.
- **Containers**: the runnable stub image runs as a non-root user.
- **No secrets in source**: ops email, ARNs, and the S3 prefix-list ID are
  parameters, not hardcoded.

## Accepted Debt / Production Hardening (out of scope here)

The following are intentionally **not** implemented in this sample and must be
added before any production use. They reduce audit/forensic visibility and use
broader-than-minimal defaults, but are not exploitable in the current sandbox
design:

- Customer-managed KMS keys (S3, ECS log group, Lambda environment variables).
- S3 server access logging and VPC flow logs.
- CloudTrail and AWS Config coverage.
- `cfn-nag` / Checkov gates in CI.
- Lambda dead-letter queues (for any future asynchronous invocation).
- Step Functions execution logging + X-Ray tracing.
- Amazon ECS Container Insights.
- A `PermissionsBoundary` on the IAM roles.
- Inline IAM policies in place of AWS-managed policies, scoped to specific ARNs.

See the "What you must add for production" section of the [README](README.md)
for the corresponding guidance.
