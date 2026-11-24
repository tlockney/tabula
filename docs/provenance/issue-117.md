# Issue 117: Token fetch failure

| Behavior | Source | Packet |
| --- | --- | --- |
| A token refresh that fails (no DNS while the radio comes back, HA unreachable, credential revoked) answers the dashboard's auth request with a failure so the frontend retries, instead of crashing the app; a failure that escapes any background task is logged, and if it ended the HA session the session restarts after the reconnect cap | independent design (#117) | #117 |
