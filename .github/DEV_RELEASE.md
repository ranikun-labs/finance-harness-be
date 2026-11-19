# DEV release

Run the `Finance DEV release` workflow manually on `main`. The native tests run against the dispatch SHA before the shared workflow builds `Dockerfile` from the repository root. Leave `deploy_enabled` false to publish only; true requests deployment of the published digest to DEV. This workflow does not deploy PROD or run Terraform or migrations.

Before enabling releases, the shared `platform-infra` workflow must be published and protected. The caller is pinned to `f28df85ed6bdfbbb842af5055d1a40ba84b59965`; publish and retain that exact commit before enabling releases. Configure the required DEV GitHub environments, access controls, OIDC roles and AWS resources separately; this file alone does not provision or authorize them. A successful release is not evidence that the product journey has been validated.
