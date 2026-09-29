# Android release signing

This repository uses a dedicated release signing identity.

- Repository: `DomEnota-maker/Instagram-`
- Key alias: `instagram_release`
- SHA-256 certificate fingerprint: `E9:BE:23:03:AC:0F:F9:CB:4C:78:FF:54:6F:9A:38:D1:AD:AB:BA:05:CE:9C:BC:7F:34:EC:3D:A7:84:42:2E:D9`
- Private keystore: **not stored in GitHub**

Every APK/AAB intended to update an already installed release must be signed with this exact key. Changing the signing key will make Android treat the build as incompatible with the installed app.

The application ID should also remain unchanged once the first installable release is distributed.
