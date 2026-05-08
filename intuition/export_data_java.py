"""Export robot data file (autopilot.dat) as a Base64-embedded Java class.

Reads the binary persistence file produced after local battles and generates
a Java class with the data encoded as a Base64 string constant. The robot
uses this as a fallback when no data file exists on the target machine
(first battle), providing VCS histogram priors from training battles.

Usage:
    python export_data_java.py <path-to-autopilot.dat>
    python export_data_java.py  (auto-detects from default Robocode location)

Output:
    robot/src/main/java/cz/zamboch/distilled/DefaultDataFile.java
"""
from __future__ import annotations

import base64
import sys
from pathlib import Path

# Default location of the data file written by Robocode
DEFAULT_DATA_PATH = Path(r"c:\robocode\robots\.data\cz\zamboch\Autopilot.data\autopilot.dat")

JAVA_OUT = (Path(__file__).parent.parent
            / "robot" / "src" / "main" / "java" / "cz" / "zamboch" / "distilled")

# Java string constant limit is ~64KB in class file; split at 60KB to be safe
CHUNK_SIZE = 60_000


def export_data_file(data_path: Path) -> None:
    if not data_path.exists():
        print(f"ERROR: Data file not found: {data_path}")
        sys.exit(1)

    raw = data_path.read_bytes()
    print(f"Read {len(raw):,} bytes from {data_path}")

    b64 = base64.b64encode(raw).decode('ascii')
    print(f"Base64 encoded: {len(b64):,} chars")

    # Split into chunks for Java string constant limit
    chunks = []
    for i in range(0, len(b64), CHUNK_SIZE):
        chunks.append(b64[i:i + CHUNK_SIZE])
    print(f"Split into {len(chunks)} chunk(s)")

    # Generate Java class
    JAVA_OUT.mkdir(parents=True, exist_ok=True)
    java_path = JAVA_OUT / "DefaultDataFile.java"

    lines = []
    lines.append("package cz.zamboch.distilled;")
    lines.append("")
    lines.append("/**")
    lines.append(" * Auto-generated embedded data file for first-battle fallback.")
    lines.append(f" * Contains {len(raw):,} bytes of persistence data (VCS histograms,")
    lines.append(" * tick budget ceiling, gun/movement stats) from training battles.")
    lines.append(" *")
    lines.append(" * <p>When no data file exists on the target machine (first battle),")
    lines.append(" * the robot decodes this Base64 data to warm-start all persisted")
    lines.append(" * subsystems with learned priors.</p>")
    lines.append(" */")
    lines.append("public final class DefaultDataFile {")
    lines.append("")
    lines.append("    private DefaultDataFile() {}")
    lines.append("")

    if len(chunks) == 1:
        lines.append(f'    private static final String DATA = "{chunks[0]}";')
    else:
        for i, chunk in enumerate(chunks):
            lines.append(f'    private static final String D{i} = "{chunk}";')
        concat = " + ".join(f"D{i}" for i in range(len(chunks)))
        lines.append(f"    private static final String DATA = {concat};")

    lines.append("")
    lines.append("    /**")
    lines.append("     * Decode the embedded data file to a byte array.")
    lines.append("     *")
    lines.append("     * @return raw persistence bytes, or empty array on decode failure")
    lines.append("     */")
    lines.append("    public static byte[] decode() {")
    lines.append("        try {")
    lines.append("            return java.util.Base64.getDecoder().decode(DATA);")
    lines.append("        } catch (Exception e) {")
    lines.append("            return new byte[0];")
    lines.append("        }")
    lines.append("    }")
    lines.append("}")
    lines.append("")

    java_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"Wrote {java_path}")


if __name__ == "__main__":
    if len(sys.argv) > 1:
        path = Path(sys.argv[1])
    else:
        path = DEFAULT_DATA_PATH
    export_data_file(path)
