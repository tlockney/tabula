# Task #128: register_result decodes the open capability set

| Behavior | Source | Packet |
| --- | --- | --- |
| A registration the server accepts is adopted even when the accepted capability list names values this build does not know: register_result capabilities decode as the open wire set, unknown names stay strings, and the session registers instead of failing and looping "connection lost" forever | F-HA-02, PROTOCOL 4.2, PROTOCOL 8 | #128 |
| The client logs the reason a session attempt failed (class and message, never a credential) instead of a bare "connection lost" | independent design (#128) | #128 |

