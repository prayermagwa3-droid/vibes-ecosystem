# Quantum lab worker assets

This directory contains the non-Android evaluation, research and training scripts migrated out of the retired `VibesQuantumEngine` Android project. They are preserved as server-side worker assets and test fixtures.

**Important:** copying these scripts into the server repository does not deploy or enable them. The Ktor training-job control plane remains disabled until an operator configures a trusted worker endpoint, credentials, resource limits, artifact storage and end-to-end tests. Run Python tools in an isolated worker environment, not inside the Ktor request process. Review dependencies and data-handling before deployment.

- `evaluation-lab/`: evaluation harness and tests
- `research-service/`: research pipeline and tests
- `training-service/`: trainer implementations, requirements and tests
- `reports/`: historical smoke/evaluation outputs
