#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""aosp_verify.py: what XGIMI's Android 11 recovery does with otacerts.zip and a whole-file signature,
re-implemented for the Mac (docs/play6/impl/ota-phase1.md). Library for recovery_trust.py, sign_ota.py,
verify_ota.py and their tests; no device access, no private key.

Follows AOSP android-11 bootable/recovery, the code the Play 6 recovery carries (its strings: "Modulus
should be 2048 or 4096 bits long", "Public exponent should be 3 or 65537", "Failed to load keys from",
"x509.pem", "signature length doesn't match EOCD marker", "EOCD marker occurs after start of EOCD",
"whole-file signature verified against RSA key"):

  install/verifier.cpp LoadKeysFromZipfile: every entry whose name ends in "x509.pem" is a PEM certificate;
      ONE entry that does not load drops ALL keys (the recovery then installs nothing, XGIMI packages included).
  LoadCertificateFromBuffer: digest from the certificate's own signature algorithm (md5/sha1WithRSA -> SHA-1,
      sha256WithRSAEncryption / ecdsa-with-SHA256 -> SHA-256, anything else refused); RSA keys must have a
      2048- or 4096-bit modulus and e = 3 or 65537 (CheckRSAKey); EC keys must be P-256 (CheckECKey).
  verify_file: 6-byte footer (signature_start, ff ff, comment_size); the signed part is the whole file
      except the EOCD comment-length field and the comment; no second EOCD marker inside the comment;
      read_pkcs7 walks ContentInfo -> SignedData -> first SignerInfo and takes its encryptedDigest, so
      signed attributes are NOT allowed (the 4th element after the version must be the signature); then
      RSA_verify (PKCS#1 v1.5, DigestInfo) against each key in turn.

Only RSA signatures are verified here (both the XGIMI and the Lumen Play 6 certificates are RSA); an EC
certificate is checked for loadability only.
"""
import hashlib
import io
import re
import zipfile
import base64

OID = {
    "1.2.840.113549.1.1.1": "rsaEncryption",
    "1.2.840.113549.1.1.4": "md5WithRSAEncryption",
    "1.2.840.113549.1.1.5": "sha1WithRSAEncryption",
    "1.2.840.113549.1.1.11": "sha256WithRSAEncryption",
    "1.2.840.113549.1.1.12": "sha384WithRSAEncryption",
    "1.2.840.113549.1.1.13": "sha512WithRSAEncryption",
    "1.3.14.3.2.3": "md5WithRSA",
    "1.3.14.3.2.29": "sha1WithRSA",
    "1.2.840.10045.2.1": "id-ecPublicKey",
    "1.2.840.10045.3.1.7": "prime256v1",
    "1.2.840.10045.4.3.2": "ecdsa-with-SHA256",
    "1.2.840.113549.1.7.2": "signedData",
    "1.2.840.113549.1.7.1": "data",
    "2.16.840.1.101.3.4.2.1": "sha256",
    "1.3.14.3.2.26": "sha1",
}
# LoadCertificateFromBuffer: signature nid -> digest the recovery uses with that key
SIG_HASH = {"md5WithRSA": "sha1", "md5WithRSAEncryption": "sha1", "sha1WithRSA": "sha1",
            "sha1WithRSAEncryption": "sha1", "sha256WithRSAEncryption": "sha256", "ecdsa-with-SHA256": "sha256"}
DIGESTINFO = {  # DER prefix of DigestInfo for RSA PKCS#1 v1.5 (RFC 8017 9.2 note 1)
    "sha1": bytes.fromhex("3021300906052b0e03021a05000414"),
    "sha256": bytes.fromhex("3031300d060960864801650304020105000420"),
}
FOOTER_SIZE = 6
EOCD_HEADER_SIZE = 22
EOCD_MAGIC = b"PK\x05\x06"
CERT_SUFFIX = "x509.pem"          # StartIteration(handle, &cookie, "", "x509.pem")


class VerifyError(Exception):
    pass


# ------------------------------------------------------------------ minimal DER
def der(buf, i):
    """(tag, content_start, content_end) of the element at i (definite lengths, up to 4 length octets)."""
    if i + 2 > len(buf):
        raise VerifyError("DER: truncated header")
    tag = buf[i]
    ln = buf[i + 1]
    j = i + 2
    if ln & 0x80:
        n = ln & 0x7F
        if n == 0 or n > 4 or j + n > len(buf):
            raise VerifyError("DER: unsupported length")
        ln = int.from_bytes(buf[j:j + n], "big")
        j += n
    if j + ln > len(buf):
        raise VerifyError("DER: element runs past its container")
    return tag, j, j + ln


def der_children(buf, start, end):
    out = []
    i = start
    while i < end:
        t, s, e = der(buf, i)
        out.append((t, s, e))
        i = e
    return out


def oid_str(b):
    first = b[0]
    parts = [str(first // 40), str(first % 40)]
    v = 0
    for x in b[1:]:
        v = (v << 7) | (x & 0x7F)
        if not x & 0x80:
            parts.append(str(v))
            v = 0
    return ".".join(parts)


def pem_blocks(text, label="CERTIFICATE"):
    if isinstance(text, bytes):
        text = text.decode("ascii", "replace")
    return [base64.b64decode("".join(m.group(1).split()))
            for m in re.finditer(r"-----BEGIN %s-----(.*?)-----END %s-----" % (label, label), text, re.S)]


# ------------------------------------------------------------------ certificates
def parse_cert(cert_der):
    """Facts of an X.509 certificate as the recovery sees them."""
    t, s, e = der(cert_der, 0)
    if t != 0x30:
        raise VerifyError("certificate: not a SEQUENCE")
    top = der_children(cert_der, s, e)
    if len(top) != 3:
        raise VerifyError("certificate: expected tbs, algorithm, signature")
    tbs = der_children(cert_der, top[0][1], top[0][2])
    k = 1 if tbs and tbs[0][0] == 0xA0 else 0          # [0] version present
    spki = tbs[k + 5]
    sig_oid_el = der_children(cert_der, top[1][1], top[1][2])[0]      # signatureAlgorithm.algorithm
    sig_oid = oid_str(cert_der[sig_oid_el[1]:sig_oid_el[2]])
    sig_alg = OID.get(sig_oid, sig_oid)
    spki_c = der_children(cert_der, spki[1], spki[2])
    alg_c = der_children(cert_der, spki_c[0][1], spki_c[0][2])
    key_oid = OID.get(oid_str(cert_der[alg_c[0][1]:alg_c[0][2]]), "?")
    bits = cert_der[spki_c[1][1] + 1:spki_c[1][2]]       # BIT STRING without the unused-bits octet
    info = {"sig_alg": sig_alg, "key_type": key_oid, "der_sha256": hashlib.sha256(cert_der).hexdigest(),
            "der": cert_der}
    if key_oid == "rsaEncryption":
        _, rs, re_ = der(bits, 0)
        n_el, e_el = der_children(bits, rs, re_)[:2]
        n = int.from_bytes(bits[n_el[1]:n_el[2]], "big")
        ex = int.from_bytes(bits[e_el[1]:e_el[2]], "big")
        info.update(n=n, e=ex, bits=n.bit_length())
    elif key_oid == "id-ecPublicKey":
        curve = OID.get(oid_str(cert_der[alg_c[1][1]:alg_c[1][2]]), "?") if len(alg_c) > 1 else "?"
        info.update(curve=curve)
    # subject CN for messages
    subj = cert_der[tbs[k + 4][1]:tbs[k + 4][2]]
    m = re.search(rb"\x06\x03\x55\x04\x03[\x0c\x13](.)(.*)", subj, re.S)
    info["cn"] = m.group(2)[:m.group(1)[0]].decode("utf-8", "replace") if m else "?"
    return info


def recovery_load_problem(info):
    """None if LoadCertificateFromBuffer would load it, else the recovery's reason."""
    if info["sig_alg"] not in SIG_HASH:
        return "Unrecognized signature nid %s" % info["sig_alg"]
    if info["key_type"] == "rsaEncryption":
        if info["bits"] not in (2048, 4096):
            return "Modulus should be 2048 or 4096 bits long, actual: %d" % info["bits"]
        if info["e"] not in (3, 65537):
            return "Public exponent should be 3 or 65537, actual: %d" % info["e"]
        return None
    if info["key_type"] == "id-ecPublicKey":
        return None if info.get("curve") == "prime256v1" else "Unsupported curve %s" % info.get("curve")
    return "Unsupported key type %s" % info["key_type"]


def load_cert_pem(pem):
    blocks = pem_blocks(pem)
    if not blocks:
        raise VerifyError("Failed to read x509 certificate")
    return parse_cert(blocks[0])                       # PEM_read_bio_X509 takes the first one


def load_otacerts(zip_bytes):
    """LoadKeysFromZipfile: (keys, entries). keys = [] when any entry fails, as in the recovery."""
    entries = []
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
        for zi in z.infolist():
            if not zi.filename.endswith(CERT_SUFFIX):
                entries.append({"name": zi.filename, "skipped": True})
                continue
            data = z.read(zi)
            try:
                info = load_cert_pem(data)
                prob = recovery_load_problem(info)
            except VerifyError as ex:
                info, prob = None, str(ex)
            entries.append({"name": zi.filename, "info": info, "problem": prob, "pem": data})
    keys = [e["info"] for e in entries if not e.get("skipped")]
    if any(e.get("problem") for e in entries if not e.get("skipped")):
        keys = []
    return keys, entries


# ------------------------------------------------------------------ whole-file signature
def read_pkcs7(blob):
    """encryptedDigest of the first SignerInfo, walking the structure exactly like verifier.cpp."""
    t, s, e = der(blob, 0)
    if t & 0x7F != 0x30:
        raise VerifyError("Could not find signature DER block (ContentInfo)")
    c = der_children(blob, s, e)
    if len(c) < 2 or c[1][0] & 0xE0 != 0xA0:          # asn1_sequence_next (OID), asn1_constructed_get
        raise VerifyError("Could not find signature DER block ([0] content)")
    t, s, e = der(blob, c[1][1])
    if t & 0x7F != 0x30:
        raise VerifyError("Could not find signature DER block (SignedData)")
    sd = der_children(blob, s, e)
    i = 3                                               # version, digestAlgorithms, contentInfo
    while i < len(sd) and sd[i][0] & 0xE0 == 0xA0:      # asn1_constructed_skip_all: certificates, crls
        i += 1
    if i >= len(sd) or sd[i][0] & 0x7F != 0x31:
        raise VerifyError("Could not find signature DER block (signerInfos SET)")
    sis = der_children(blob, sd[i][1], sd[i][2])
    if not sis or sis[0][0] & 0x7F != 0x30:
        raise VerifyError("Could not find signature DER block (SignerInfo)")
    si = der_children(blob, sis[0][1], sis[0][2])
    if len(si) < 5 or si[4][0] != 0x04:                 # 4 x asn1_sequence_next, asn1_octet_string_get
        raise VerifyError("Could not find signature DER block (encryptedDigest; signed attributes present?)")
    return blob[si[4][1]:si[4][2]], pkcs7_certs(blob, sd[3:i])


def pkcs7_certs(blob, constructed):
    """DER certificates of the [0] certificates field (not used by the recovery; for reports)."""
    out = []
    for t, s, e in constructed:
        if t != 0xA0:
            continue
        i = s
        while i < e:
            _, _, ce = der(blob, i)
            out.append(blob[i:ce])
            i = ce
    return out


def rsa_pkcs1_ok(sig, digest_name, digest, n, e):
    k = (n.bit_length() + 7) // 8
    if len(sig) != k:
        return False
    m = pow(int.from_bytes(sig, "big"), e, n).to_bytes(k, "big")
    t = DIGESTINFO[digest_name] + digest
    if k < len(t) + 11:
        return False
    return m == b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t


def whole_file_parts(path):
    """(signed_len, sig_blob, comment) from the footer, with every check of verify_file."""
    with open(path, "rb") as f:
        f.seek(0, 2)
        length = f.tell()
        if length < FOOTER_SIZE:
            raise VerifyError("not big enough to contain footer")
        f.seek(length - FOOTER_SIZE)
        footer = f.read(FOOTER_SIZE)
        if footer[2] != 0xFF or footer[3] != 0xFF:
            raise VerifyError("footer is wrong")
        comment_size = footer[4] | footer[5] << 8
        signature_start = footer[0] | footer[1] << 8
        if signature_start > comment_size:
            raise VerifyError("signature length doesn't match EOCD marker")
        if signature_start <= FOOTER_SIZE:
            raise VerifyError("Signature start is in the footer")
        eocd_size = comment_size + EOCD_HEADER_SIZE
        if length < eocd_size:
            raise VerifyError("not big enough to contain EOCD")
        signed_len = length - eocd_size + EOCD_HEADER_SIZE - 2
        f.seek(length - eocd_size)
        eocd = f.read(eocd_size)
    if eocd[:4] != EOCD_MAGIC:
        raise VerifyError("signature length doesn't match EOCD marker")
    for i in range(4, eocd_size - 3):
        if eocd[i:i + 4] == EOCD_MAGIC:
            raise VerifyError("EOCD marker occurs after start of EOCD")
    blob = eocd[eocd_size - signature_start:eocd_size - FOOTER_SIZE]
    return signed_len, blob, eocd[EOCD_HEADER_SIZE:]


def file_digests(path, n):
    h1, h256 = hashlib.sha1(), hashlib.sha256()
    with open(path, "rb") as f:
        left = n
        while left:
            b = f.read(min(left, 1 << 20))
            if not b:
                raise VerifyError("short read")
            h1.update(b)
            h256.update(b)
            left -= len(b)
    return {"sha1": h1.digest(), "sha256": h256.digest()}


def verify_file(path, keys):
    """Index of the key that verifies the whole-file signature (as the recovery logs it), else raises."""
    if not keys:
        raise VerifyError("no keys (an otacerts.zip with one bad entry loads none)")
    signed_len, blob, _ = whole_file_parts(path)
    sig, _certs = read_pkcs7(blob)
    dg = file_digests(path, signed_len)
    for i, k in enumerate(keys):
        if k["key_type"] != "rsaEncryption":
            continue
        h = SIG_HASH[k["sig_alg"]]
        if rsa_pkcs1_ok(sig, h, dg[h], k["n"], k["e"]):
            return i
    raise VerifyError("failed to verify whole-file signature")
