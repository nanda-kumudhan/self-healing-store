# Project scope

This is a student learning project, not a product. The user explicitly authorized implementing the core algorithms as well as the setup. The earlier hands-on placeholder restriction has been lifted.

- Do not create a dashboard or status web page until the user explicitly asks.
- Do not add Kafka. The user chose the original backend without it.
- Skip ML and AI prediction features until requested.
- Keep client-side keys and plaintext out of coordinator and node requests, disks and logs.
- Never replace integrity, authorization or authentication checks with permissive defaults.
- Keep chunk processing bounded as files grow. Preserve atomic publication of verified downloads.
- Keep measurements, model scenarios and infrastructure smoke tests clearly distinguished. Do not fabricate results or recordings.
- Six nodes can serve reads after two holder losses, but need replacement capacity or a seventh node to restore five distinct holders.

Write docs and comments in plain language, like a CS student explaining the project. Use first person in project notes where it fits. Say clearly what remains unfinished.

Use Java 21 to build. Run `bash scripts/check.sh`. The real process-cluster test is `python3 scripts/e2e.py`. Docker tests are opt-in with `./mvnw -Pintegration verify` and need an accessible daemon. Do not change host Docker permissions to make tests pass.
