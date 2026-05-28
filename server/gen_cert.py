"""
Génère un certificat TLS auto-signé (cert.pem + key.pem) dans le dossier courant.
Inclut le SAN avec l'IP du serveur pour que Android accepte le certificat.

Usage : python gen_cert.py [IP]
  ex : python gen_cert.py 172.16.1.105
"""

import sys
import datetime
import ipaddress
from pathlib import Path
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa

SERVER_IP = sys.argv[1] if len(sys.argv) > 1 else "172.16.1.105"
OUT_DIR = Path(__file__).parent

key_path = OUT_DIR / "key.pem"
cert_path = OUT_DIR / "cert.pem"

# Génération de la clé privée RSA 2048
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

# Construction du certificat
subject = issuer = x509.Name([
    x509.NameAttribute(NameOID.COMMON_NAME, "hasan-dev"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Hasan Dev"),
])

cert = (
    x509.CertificateBuilder()
    .subject_name(subject)
    .issuer_name(issuer)
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(datetime.datetime.now(datetime.UTC))
    .not_valid_after(datetime.datetime.now(datetime.UTC) + datetime.timedelta(days=730))
    # SAN obligatoire : Android rejette les certificats sans subjectAltName
    .add_extension(
        x509.SubjectAlternativeName([
            x509.IPAddress(ipaddress.IPv4Address(SERVER_IP)),
            x509.DNSName("hasan-dev"),
        ]),
        critical=False,
    )
    .sign(key, hashes.SHA256())
)

# Écriture clé privée
key_path.write_bytes(
    key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    )
)

# Écriture certificat
cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))

print(f"[OK] Certificat généré pour IP : {SERVER_IP}")
print(f"     {cert_path}")
print(f"     {key_path}")
print(f"     Valide jusqu'au : {cert.not_valid_after_utc.date()}")
