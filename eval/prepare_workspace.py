"""Copy up overlay directories before Java captures their immutable workspace identity."""
import os
from pathlib import Path
import sys


def prepare(root):
    root = Path(root)
    if not root.is_dir() or root.is_symlink():
        raise ValueError("Expected a real task workspace directory")
    directories = [root] + [p for p in root.rglob("*") if p.is_dir() and not p.is_symlink()]
    for directory in directories:
        # OverlayFS can change birth time on first copy-up even though inode remains unchanged.
        # Do this before SessionService/tools bind to the workspace, never during an active run.
        os.utime(directory, None, follow_symlinks=False)


if __name__ == "__main__":
    prepare(sys.argv[1])
