Place stunnel.exe here for Windows 7 legacy TLS tunnel mode.

Expected runtime layout:
tools\stunnel\stunnel.exe
tools\stunnel\ca-certs.pem

If ca-certs.pem is present, VisionGuard generates a stunnel config that validates
the remote certificate chain and hostname. Without ca-certs.pem, stunnel is
started without chain verification as a compatibility fallback.
