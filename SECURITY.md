# Security

Please report security problems privately through GitHub's
**Security → Report a vulnerability** on this repository rather than in a public issue.

Notes for users:
- Only install the APK from this repository's Releases page, and check the SHA-256 if you want to
  be sure. Official builds are signed with one key; Android refuses to install an update signed
  with a different key over an existing install.
- RTMP, RTSP and SRT without a passphrase are not encrypted. Stream to a server on your own
  network, or use `rtmps://` / SRT with a passphrase when going over the internet.
