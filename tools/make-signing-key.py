#!/usr/bin/env python3
"""Creates the project signing key used for everyday builds.

Why this exists
---------------
Android refuses to replace an installed app with a build that carries a
different signature, so a freshly generated debug key on every machine and
every CI run means: uninstall, reinstall, lose your place. Android Studio
solves this by shipping a well-known debug keystore whose password is
"android". This project needs the same trick, but CI has no Studio, so the
key is generated here and committed.

What it is NOT
--------------
It is not a secret and not the release key. Anyone can read it, exactly like
Android's own debug key. A real release key (see README "Signing a release
APK") overrides this one as soon as its credentials are provided.

Usage: python3 tools/make-signing-key.py [output-file]
"""

import datetime
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

OUTPUT = sys.argv[1] if len(sys.argv) > 1 else "app/bloodline-signing.p12"

# Mirrors Android's own debug keystore: the password is public on purpose.
PASSWORD = b"bloodline"
ALIAS = "bloodline"

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

name = x509.Name(
    [
        x509.NameAttribute(NameOID.COMMON_NAME, "Bloodlines Android"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Bloodlines Android"),
        x509.NameAttribute(NameOID.COUNTRY_NAME, "NG"),
    ]
)

now = datetime.datetime.now(datetime.timezone.utc)
certificate = (
    x509.CertificateBuilder()
    .subject_name(name)
    .issuer_name(name)
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(now - datetime.timedelta(days=1))
    # Long lived on purpose: a key that expires cannot update an installed app.
    .not_valid_after(now + datetime.timedelta(days=365 * 30))
    .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
    .sign(key, hashes.SHA256())
)

p12 = pkcs12.serialize_key_and_certificates(
    name=ALIAS.encode(),
    key=key,
    cert=certificate,
    cas=None,
    encryption_algorithm=serialization.BestAvailableEncryption(PASSWORD),
)

with open(OUTPUT, "wb") as handle:
    handle.write(p12)

print(f"Wrote {OUTPUT} ({len(p12)} bytes), alias {ALIAS!r}, store type PKCS12.")
print("This key is public on purpose: it keeps every build installable over the last.")
