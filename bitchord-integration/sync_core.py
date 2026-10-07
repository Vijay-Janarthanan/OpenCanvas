"""Copy the OpenCanvas core into a BitChord checkout (BitChord vendors it instead of depending on a Maven artifact).

    python bitchord-integration/sync_core.py /path/to/BitChord

Copies every .kt file of the core's `commonMain` and `jvmSharedMain` source sets into
`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/opencanvas/`, flat: the files keep their
`com.opencanvas.core.*` packages, so BitChord code imports the library exactly as any other host would.
The `onnx` and `reframer` folders (the optional subject-centred reframing) are not part of the synced-video
path and are left out.
"""
import shutil
import sys
from pathlib import Path

SKIP = ("onnx", "reframer")


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    repo = Path(__file__).resolve().parent.parent
    target = Path(sys.argv[1]) / "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/opencanvas"
    if not target.is_dir():
        print(f"not a BitChord checkout with the OpenCanvas folder: {target}")
        return 1
    copied = 0
    for source_set in ("commonMain", "jvmSharedMain"):
        root = repo / "packages/opencanvas-core/src" / source_set / "kotlin"
        for path in root.rglob("*.kt"):
            if any(part in SKIP for part in path.parts):
                continue
            shutil.copyfile(path, target / path.name)
            copied += 1
    print(f"{copied} files copied to {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
