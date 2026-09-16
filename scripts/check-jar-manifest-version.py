#!/usr/bin/env python3
"""Verify that a built JAR carries the expected Implementation-Version."""

from __future__ import annotations

import pathlib
import sys
import zipfile


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def parse_manifest(data: bytes) -> dict[str, str]:
    text = data.decode("utf-8")
    unfolded: list[str] = []
    for line in text.replace("\r\n", "\n").split("\n"):
        if not line:
            break
        if line.startswith(" "):
            if not unfolded:
                fail("invalid continuation line in JAR manifest")
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)

    attributes: dict[str, str] = {}
    for line in unfolded:
        if ": " not in line:
            fail(f"invalid JAR manifest line: {line}")
        name, value = line.split(": ", 1)
        if name in attributes:
            fail(f"duplicate JAR manifest attribute: {name}")
        attributes[name] = value
    return attributes


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: check-jar-manifest-version.py JAR EXPECTED_VERSION")

    jar = pathlib.Path(sys.argv[1])
    expected = sys.argv[2]
    if not jar.is_file() or jar.stat().st_size == 0:
        fail(f"JAR is missing or empty: {jar}")
    if not expected:
        fail("expected version is empty")

    try:
        with zipfile.ZipFile(jar) as archive:
            manifest = parse_manifest(archive.read("META-INF/MANIFEST.MF"))
    except KeyError:
        fail(f"JAR manifest is missing: {jar}")
    except (OSError, UnicodeError, zipfile.BadZipFile) as exc:
        fail(f"unable to read JAR manifest: {jar}: {exc}")

    actual = manifest.get("Implementation-Version")
    if actual != expected:
        fail(
            "JAR Implementation-Version does not match version.ini: "
            f"jar={jar} expected={expected} actual={actual or 'missing'}"
        )

    print(f"jar_manifest_version=OK jar={jar} version={actual}")


if __name__ == "__main__":
    main()
