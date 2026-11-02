#!/usr/bin/env python3
"""Validate, archive and extract the shared Android/desktop changelog."""

import argparse
import datetime
from pathlib import Path
import re


LOCALES = ("en", "zh-CN")
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
UNRELEASED = "## [Unreleased]\n"


def version_tuple(version):
    if not re.fullmatch(VERSION, version):
        raise ValueError(f"Invalid version: {version}; expected X.Y.Z without a v prefix")
    return tuple(map(int, version.split(".")))


def read_changelogs(root):
    documents = []
    for locale in LOCALES:
        path = root / "docs/changelog" / f"{locale}.md"
        source = path.read_bytes().decode("utf-8")
        parts = re.split(r"^(## .*)$", source, flags=re.MULTILINE)
        if "\r" in source or not source.endswith("\n") or not re.fullmatch(r"# [^\n]+\n\s*", parts[0]):
            raise ValueError(f"{path}: expected one title and LF line endings, including a final newline")
        sections = {}
        previous = None
        for heading, body in zip(parts[1::2], parts[2::2]):
            date = None
            if not sections:
                if heading != UNRELEASED.rstrip("\n"):
                    raise ValueError(f"{path}: first section must be ## [Unreleased]")
                version = "Unreleased"
            else:
                match = re.fullmatch(rf"## \[({VERSION})\] - (\d{{4}}-\d{{2}}-\d{{2}})", heading)
                if not match:
                    raise ValueError(f"{path}: invalid release heading: {heading}")
                version, date = match.groups()
                datetime.date.fromisoformat(date)
                current = version_tuple(version)
                if previous is not None and current >= previous:
                    raise ValueError(f"{path}: releases must be unique and ordered newest first")
                previous = current
            entries = []
            for line in body.splitlines():
                if not line.strip():
                    continue
                if not re.fullmatch(r"- \S.*", line):
                    raise ValueError(f"{path}: {version} entries must be single-line '- ' bullets: {line}")
                entries.append(line)
            if version != "Unreleased" and not entries:
                raise ValueError(f"{path}: {version} has no entries")
            sections[version] = (date, entries)
        if not sections:
            raise ValueError(f"{path}: missing ## [Unreleased]")
        documents.append((path, source, sections))
    shapes = [[(version, date, len(entries)) for version, (date, entries) in sections.items()]
              for _, _, sections in documents]
    if shapes[0] != shapes[1]:
        raise ValueError("English and Chinese must have the same versions, dates and entry counts in the same order")
    return documents


def check(root, version=None):
    documents = read_changelogs(root)
    if version is not None:
        target = version_tuple(version)
        sections = documents[0][2]
        if not sections["Unreleased"][1]:
            raise ValueError("Unreleased is empty; add release notes in both languages first")
        if any(target <= version_tuple(released) for released in sections if released != "Unreleased"):
            raise ValueError(f"Version {version} must be newer than every archived release")
    return documents


def archive(root, version, date=None):
    documents = check(root, version)
    date = date or datetime.date.today()
    heading = f"{UNRELEASED}\n## [{version}] - {date.isoformat()}\n"
    # Validate both languages before changing either file; keep the existing text intact.
    for path, source, _ in documents:
        archived = re.sub(r"^## \[Unreleased\]\n", lambda _: heading, source, count=1, flags=re.MULTILINE)
        path.write_bytes(archived.encode("utf-8"))


def notes(root, version):
    version_tuple(version)
    documents = read_changelogs(root)
    if version not in documents[0][2]:
        raise ValueError(f"Release {version} is not in the changelog")
    english = "\n".join(documents[0][2][version][1])
    chinese = "\n".join(documents[1][2][version][1])
    return f"## 简体中文\n\n{chinese}\n\n## English\n\n{english}\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check", help="validate both languages and optionally a planned release").add_argument("version", nargs="?")
    commands.add_parser("archive", help="move Unreleased entries into a dated release").add_argument("version")
    commands.add_parser("notes", help="print bilingual GitHub Release notes").add_argument("version")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        if args.command == "check":
            check(root, args.version)
            print("Changelog OK")
        elif args.command == "archive":
            archive(root, args.version)
        else:
            print(notes(root, args.version), end="")
    except (OSError, ValueError) as error:
        parser.exit(1, f"changelog: {error}\n")


if __name__ == "__main__":
    main()
