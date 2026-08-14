# Release signing

Official ThorLens Extended APKs use one persistent release key held as encrypted GitHub Actions repository secrets. The workflow restores it only inside the runner's temporary directory and removes it in an `always()` cleanup step.

Public certificate: [`thorlens-extended-release.pem`](thorlens-extended-release.pem)

SHA-256 certificate fingerprint:

```text
3D:89:52:F1:07:47:45:26:64:0D:75:D6:A2:BB:27:9B:1C:F5:4C:19:00:27:62:6C:A0:55:6C:9C:B0:E0:63:32
```

Verify a downloaded APK with Android SDK Build Tools:

```shell
apksigner verify --verbose --print-certs ThorLens-Extended.apk
```

The reported signer certificate SHA-256 digest must match the fingerprint above. Preserve the GitHub secrets: replacing the key would prevent in-place updates of installed releases.
