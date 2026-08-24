import os
import struct
import shutil
import hashlib

MAGIC = b"OggS"

CHUNK_SIZE = 4 * 1024 * 1024
COPY_SIZE = 1024 * 1024


# =========================================================
# PATH
# =========================================================

def normalize_path(path):
    path = path.strip().strip('"').strip("'")

    if path.startswith("/sdcard/"):
        path = "/storage/emulated/0/" + path[8:]

    return os.path.normpath(path)


# =========================================================
# SIZE
# =========================================================

def fmt_size(size):

    if size >= 1024 ** 3:
        return f"{size / (1024 ** 3):.2f} GB"

    if size >= 1024 ** 2:
        return f"{size / (1024 ** 2):.2f} MB"

    if size >= 1024:
        return f"{size / 1024:.2f} KB"

    return f"{size} B"


# =========================================================
# GLOBAL DATA
# =========================================================

PAGES = []
CANDIDATES = []


# =========================================================
# PROGRESS
# =========================================================

def show_progress(current, total, label="Progress"):

    if total <= 0:
        percent = 0
    else:
        percent = min(
            100,
            int(current * 100 / total)
        )

    width = 30

    filled = int(
        width * percent / 100
    )

    bar = (
        "#" * filled +
        "-" * (width - filled)
    )

    print(
        f"\r{label}: "
        f"[{bar}] "
        f"{percent:3d}%",
        end="",
        flush=True
    )


# =========================================================
# READ OGG PAGE
# =========================================================

def read_page(f, offset, file_size):

    if offset + 27 > file_size:
        return None

    try:

        f.seek(offset)

        header = f.read(27)

        if len(header) != 27:
            return None

        if header[:4] != MAGIC:
            return None

        if header[4] != 0:
            return None

        header_type = header[5]

        serial = struct.unpack_from(
            "<I",
            header,
            14
        )[0]

        sequence = struct.unpack_from(
            "<I",
            header,
            18
        )[0]

        segment_count = header[26]

        segments = f.read(
            segment_count
        )

        if len(segments) != segment_count:
            return None

        payload_size = sum(
            segments
        )

        end = (
            offset +
            27 +
            segment_count +
            payload_size
        )

        if end > file_size:
            return None

        return {
            "offset": offset,
            "end": end,
            "size": end - offset,
            "serial": serial,
            "sequence": sequence,
            "header_type": header_type
        }

    except Exception:
        return None


# =========================================================
# SCAN OggS
# =========================================================

def scan_pages(path):

    global PAGES

    PAGES = []

    file_size = os.path.getsize(path)

    position = 0

    overlap = len(MAGIC) - 1

    buffer = b""
    buffer_start = 0

    last_progress = 0

    try:

        with open(path, "rb") as f:

            while position < file_size:

                f.seek(position)

                chunk = f.read(
                    CHUNK_SIZE
                )

                if not chunk:
                    break

                data = (
                    buffer +
                    chunk
                )

                search = 0

                while True:

                    found = data.find(
                        MAGIC,
                        search
                    )

                    if found == -1:
                        break

                    absolute = (
                        buffer_start +
                        found
                    )

                    page = read_page(
                        f,
                        absolute,
                        file_size
                    )

                    if page is not None:
                        PAGES.append(page)

                    search = found + 1

                if len(data) >= overlap:

                    buffer = data[-overlap:]

                    buffer_start = (
                        position +
                        len(chunk) -
                        overlap
                    )

                else:

                    buffer = data
                    buffer_start = position

                position += len(chunk)

                if (
                    position -
                    last_progress
                    >=
                    8 * 1024 * 1024
                ):

                    show_progress(
                        position,
                        file_size,
                        "Scanning"
                    )

                    last_progress = position

        show_progress(
            file_size,
            file_size,
            "Scanning"
        )

        print()

        return True

    except Exception as e:

        print()
        print()
        print("SCAN ERROR:")
        print(e)

        return False


# =========================================================
# BUILD INDIVIDUAL STREAMS
# =========================================================

def build_streams():

    global CANDIDATES

    CANDIDATES = []

    if not PAGES:
        return

    pages = sorted(
        PAGES,
        key=lambda p: p["offset"]
    )

    current = None

    for page in pages:

        bos = bool(
            page["header_type"] & 0x02
        )

        eos = bool(
            page["header_type"] & 0x04
        )

        serial = page["serial"]

        if bos:

            if current is not None:

                CANDIDATES.append(
                    current
                )

            current = {
                "serial": serial,
                "pages": [page],
                "start": page["offset"],
                "end": page["end"]
            }

            if eos:

                CANDIDATES.append(
                    current
                )

                current = None

            continue

        if current is None:
            continue

        if serial != current["serial"]:
            continue

        previous = current["pages"][-1]

        expected = (
            previous["sequence"] + 1
        ) & 0xFFFFFFFF

        if page["sequence"] != expected:

            CANDIDATES.append(
                current
            )

            current = None

            continue

        current["pages"].append(
            page
        )

        current["end"] = page["end"]

        if eos:

            CANDIDATES.append(
                current
            )

            current = None

    if current is not None:

        CANDIDATES.append(
            current
        )


# =========================================================
# VALIDATE STREAM
# =========================================================

def validate_stream(candidate):

    pages = candidate["pages"]

    if len(pages) < 2:
        return False

    if not (
        pages[0]["header_type"] & 0x02
    ):
        return False

    if not (
        pages[-1]["header_type"] & 0x04
    ):
        return False

    size = (
        candidate["end"] -
        candidate["start"]
    )

    if size < 100:
        return False

    return True


# =========================================================
# SORT STREAMS
# =========================================================

def get_valid_streams():

    valid = []

    for candidate in CANDIDATES:

        if not validate_stream(
            candidate
        ):
            continue

        candidate["size"] = (
            candidate["end"] -
            candidate["start"]
        )

        valid.append(
            candidate
        )

    valid.sort(
        key=lambda x: x["size"],
        reverse=True
    )

    return valid


# =========================================================
# READ STREAM HEADER
# =========================================================

def read_stream_head(
    path,
    candidate
):

    size = candidate["size"]

    amount = min(
        size,
        1024 * 1024
    )

    try:

        with open(path, "rb") as f:

            f.seek(
                candidate["start"]
            )

            return f.read(amount)

    except Exception:

        return b""


# =========================================================
# CODEC DETECTION
# =========================================================

def detect_codec(data):

    if b"\x01vorbis" in data[:500]:
        return "Vorbis"

    if b"OpusHead" in data[:500]:
        return "Opus"

    if b"fLaC" in data[:500]:
        return "FLAC"

    if b"Speex" in data[:500]:
        return "Speex"

    return "UNKNOWN"


# =========================================================
# AUDIO INFO
# =========================================================

def audio_info(data):

    codec = detect_codec(data)

    channels = None
    sample_rate = None

    pos = data.find(
        b"\x01vorbis"
    )

    if pos >= 0:

        try:

            channels = data[
                pos + 11
            ]

            sample_rate = struct.unpack_from(
                "<I",
                data,
                pos + 12
            )[0]

        except Exception:
            pass

    pos = data.find(
        b"OpusHead"
    )

    if pos >= 0:

        try:

            channels = data[
                pos + 9
            ]

            sample_rate = struct.unpack_from(
                "<I",
                data,
                pos + 12
            )[0]

        except Exception:
            pass

    return codec, channels, sample_rate


# =========================================================
# SHOW STREAM LIST
# =========================================================

def show_streams(
    path,
    streams,
    limit=None
):

    print()
    print("=" * 90)
    print("OGG STREAM LIST")
    print("ORDER: LARGEST -> SMALLEST")
    print("=" * 90)

    count = len(streams)

    if limit is not None:
        count = min(
            count,
            limit
        )

    for index in range(count):

        item = streams[index]

        head = read_stream_head(
            path,
            item
        )

        codec, channels, rate = (
            audio_info(head)
        )

        ch = (
            "?"
            if channels is None
            else str(channels)
        )

        sr = (
            "?"
            if rate is None
            else str(rate)
        )

        print(
            f"{index + 1:04d} | "
            f"{fmt_size(item['size']):>10} | "
            f"{codec:<7} | "
            f"{ch:>2}ch | "
            f"{sr:>6}Hz | "
            f"0x{item['start']:X}"
        )

    print("=" * 90)


# =========================================================
# EXTRACT ONE
# =========================================================

def extract_one(
    source,
    candidate,
    output
):

    start = candidate["start"]
    total = candidate["size"]

    copied = 0

    try:

        with open(
            source,
            "rb"
        ) as src:

            with open(
                output,
                "wb"
            ) as dst:

                src.seek(start)

                remaining = total

                while remaining > 0:

                    chunk = src.read(
                        min(
                            COPY_SIZE,
                            remaining
                        )
                    )

                    if not chunk:
                        return False

                    dst.write(chunk)

                    copied += len(chunk)

                    remaining -= len(chunk)

        return copied == total

    except Exception:

        return False


# =========================================================
# SAVE MAP
# =========================================================

def save_map(
    path,
    folder,
    streams
):

    map_path = os.path.join(
        folder,
        "OGG_MAP.txt"
    )

    try:

        with open(
            map_path,
            "w",
            encoding="utf-8"
        ) as f:

            f.write(
                "=" * 80 +
                "\n"
            )

            f.write(
                "NBA 2K20 OGG RESOURCE MAP V11\n"
            )

            f.write(
                "ORDER: LARGEST -> SMALLEST\n"
            )

            f.write(
                "=" * 80 +
                "\n\n"
            )

            for i, item in enumerate(
                streams,
                start=1
            ):

                head = read_stream_head(
                    path,
                    item
                )

                codec, channels, rate = (
                    audio_info(head)
                )

                f.write(
                    f"[{i:04d}]\n"
                )

                f.write(
                    f"File       : "
                    f"audio_{i:04d}.ogg\n"
                )

                f.write(
                    f"Size       : "
                    f"{item['size']:,} bytes "
                    f"({fmt_size(item['size'])})\n"
                )

                f.write(
                    f"Codec      : "
                    f"{codec}\n"
                )

                f.write(
                    f"Channels   : "
                    f"{channels}\n"
                )

                f.write(
                    f"SampleRate : "
                    f"{rate}\n"
                )

                f.write(
                    f"Serial     : "
                    f"{item['serial']}\n"
                )

                f.write(
                    f"Offset     : "
                    f"0x{item['start']:X}\n"
                )

                f.write(
                    f"End        : "
                    f"0x{item['end']:X}\n"
                )

                f.write(
                    f"Pages      : "
                    f"{len(item['pages'])}\n"
                )

                f.write("\n")

        return map_path

    except Exception as e:

        print(
            "MAP ERROR:",
            e
        )

        return None


# =========================================================
# EXTRACT ALL
# =========================================================

def extract_all(
    path,
    streams
):

    folder = os.path.join(
        os.path.dirname(path),
        "OGG_EXTRACTED_V11"
    )

    os.makedirs(
        folder,
        exist_ok=True
    )

    print()
    print(
        "Extracting individual OGG streams..."
    )

    print(
        "Largest -> Smallest"
    )

    print()

    saved = 0

    for i, item in enumerate(
        streams,
        start=1
    ):

        filename = (
            f"audio_{i:04d}.ogg"
        )

        output = os.path.join(
            folder,
            filename
        )

        ok = extract_one(
            path,
            item,
            output
        )

        if ok:

            saved += 1

            head = read_stream_head(
                path,
                item
            )

            codec, channels, rate = (
                audio_info(head)
            )

            print(
                f"[{i:04d}/"
                f"{len(streams):04d}] "
                f"OK | "
                f"{filename} | "
                f"{fmt_size(item['size'])} | "
                f"{codec}",
                flush=True
            )

        else:

            print(
                f"[{i:04d}/"
                f"{len(streams):04d}] "
                f"FAILED | "
                f"{fmt_size(item['size'])}",
                flush=True
            )

    map_path = save_map(
        path,
        folder,
        streams
    )

    print()

    if map_path:

        print("MAP:")
        print(map_path)

    return folder, saved


# =========================================================
# SHA256 REGION
# =========================================================

def sha256_region(
    path,
    start,
    end
):

    sha = hashlib.sha256()

    try:

        with open(
            path,
            "rb"
        ) as f:

            f.seek(start)

            remaining = (
                end - start
            )

            while remaining > 0:

                chunk = f.read(
                    min(
                        COPY_SIZE,
                        remaining
                    )
                )

                if not chunk:
                    return ""

                sha.update(chunk)

                remaining -= len(chunk)

        return sha.hexdigest()

    except Exception:

        return ""


# =========================================================
# WRITE REPLACEMENT INTO OUTPUT
# =========================================================

def write_replacement(
    output,
    candidate,
    replacement
):

    start = candidate["start"]
    total = candidate["size"]

    new_size = os.path.getsize(
        replacement
    )

    if new_size > total:
        return False

    try:

        with open(
            output,
            "r+b"
        ) as dst:

            dst.seek(start)

            with open(
                replacement,
                "rb"
            ) as src:

                remaining = new_size

                while remaining > 0:

                    chunk = src.read(
                        min(
                            COPY_SIZE,
                            remaining
                        )
                    )

                    if not chunk:
                        return False

                    dst.write(chunk)

                    remaining -= len(chunk)

            padding = (
                total -
                new_size
            )

            zero_block = (
                b"\x00" *
                COPY_SIZE
            )

            while padding > 0:

                amount = min(
                    COPY_SIZE,
                    padding
                )

                dst.write(
                    zero_block[:amount]
                )

                padding -= amount

        return True

    except Exception:

        return False


# =========================================================
# REPLACE OGG - MULTIPLE
# =========================================================

def replace_ogg(
    source,
    streams
):

    print()

    show_streams(
        source,
        streams,
        limit=50
    )

    print()
    print(
        "You can replace MULTIPLE OGG files."
    )

    print(
        "Enter 0 when finished selecting."
    )

    print()

    replacements = []

    while True:

        try:

            number = int(
                input(
                    "Enter OGG number "
                    "(0 = DONE):\n> "
                ).strip()
            )

        except ValueError:

            print(
                "Invalid number."
            )

            continue

        if number == 0:
            break

        if (
            number < 1
            or number > len(streams)
        ):

            print(
                "Invalid OGG number."
            )

            continue

        # Prevent duplicate selection
        if any(
            x["number"] == number
            for x in replacements
        ):

            print(
                "That OGG is already selected."
            )

            continue

        target = streams[
            number - 1
        ]

        print()
        print(
            f"Selected OGG #{number:04d}"
        )

        print(
            f"Original size: "
            f"{fmt_size(target['size'])}"
        )

        replacement = normalize_path(
            input(
                "Replacement .ogg path:\n> "
            )
        )

        if not os.path.isfile(
            replacement
        ):

            print(
                "Replacement file not found."
            )

            continue

        if not replacement.lower().endswith(
            ".ogg"
        ):

            print(
                "Replacement must be .ogg."
            )

            continue

        new_size = os.path.getsize(
            replacement
        )

        print(
            f"New size: "
            f"{fmt_size(new_size)}"
        )

        if new_size > target["size"]:

            print()
            print(
                "❌ REPLACEMENT TOO LARGE."
            )

            print(
                f"Original: "
                f"{fmt_size(target['size'])}"
            )

            print(
                f"New:      "
                f"{fmt_size(new_size)}"
            )

            print(
                "This OGG was NOT added."
            )

            continue

        try:

            with open(
                replacement,
                "rb"
            ) as f:

                replacement_head = f.read(
                    min(
                        1024 * 1024,
                        new_size
                    )
                )

        except Exception as e:

            print(
                "Cannot read replacement:"
            )

            print(e)

            continue

        if not replacement_head.startswith(
            MAGIC
        ):

            print(
                "❌ Invalid OggS file."
            )

            continue

        codec, channels, rate = (
            audio_info(
                replacement_head
            )
        )

        print(
            f"Codec: {codec}"
        )

        print(
            f"Channels: {channels}"
        )

        print(
            f"Sample rate: {rate}"
        )

        replacements.append({
            "number": number,
            "candidate": target,
            "replacement": replacement
        })

        print()
        print(
            "✓ Added to replacement list."
        )

        print(
            f"Total selected: "
            f"{len(replacements)}"
        )

        print()

    if not replacements:

        print()
        print(
            "No replacements selected."
        )

        return

    # =====================================================
    # SHOW FINAL LIST
    # =====================================================

    print()
    print("=" * 70)
    print(
        "REPLACEMENT QUEUE"
    )
    print("=" * 70)

    for item in replacements:

        target = item["candidate"]

        print(
            f"OGG #{item['number']:04d} | "
            f"{fmt_size(target['size']):>10} | "
            f"{os.path.basename(item['replacement'])}"
        )

    print("=" * 70)

    print()
    confirm = input(
        "Start replacement? [Y/N]\n> "
    ).strip().lower()

    if confirm != "y":

        print(
            "Cancelled."
        )

        return

    # =====================================================
    # OUTPUT
    # =====================================================

    folder = os.path.join(
        os.path.dirname(source),
        "OGG_REPLACED_V11"
    )

    os.makedirs(
        folder,
        exist_ok=True
    )

    base = os.path.basename(
        source
    )

    output = os.path.join(
        folder,
        base
    )

    # =====================================================
    # COPY SOURCE ONCE
    # NO BACKUP
    # =====================================================

    print()
    print(
        "Creating output..."
    )

    try:

        shutil.copyfile(
            source,
            output
        )

    except Exception as e:

        print()
        print(
            "OUTPUT COPY ERROR:"
        )

        print(e)

        return

    # =====================================================
    # APPLY MULTIPLE REPLACEMENTS
    # =====================================================

    completed = 0

    for index, item in enumerate(
        replacements,
        start=1
    ):

        number = item["number"]
        target = item["candidate"]
        replacement = item["replacement"]

        print()
        print(
            f"[{index}/{len(replacements)}] "
            f"Replacing OGG #{number:04d}"
        )

        ok = write_replacement(
            output,
            target,
            replacement
        )

        if ok:

            completed += 1

            print(
                "✓ Replacement complete."
            )

        else:

            print(
                "❌ Replacement failed."
            )

    # =====================================================
    # VERIFY FILE SIZE
    # =====================================================

    original_size = os.path.getsize(
        source
    )

    result_size = os.path.getsize(
        output
    )

    print()
    print(
        f"Original file: "
        f"{fmt_size(original_size)}"
    )

    print(
        f"New file:      "
        f"{fmt_size(result_size)}"
    )

    if result_size != original_size:

        print()
        print(
            "❌ FILE SIZE MISMATCH!"
        )

        print(
            "Do not use the output."
        )

        return

    print()
    print(
        "✓ FILE SIZE VERIFIED."
    )

    # =====================================================
    # VERIFY TARGET REGIONS
    # =====================================================

    print()
    print(
        "Verifying replacements..."
    )

    verified = 0

    for item in replacements:

        target = item["candidate"]

        old_hash = sha256_region(
            source,
            target["start"],
            target["end"]
        )

        new_hash = sha256_region(
            output,
            target["start"],
            target["end"]
        )

        if (
            old_hash and
            new_hash and
            old_hash != new_hash
        ):

            verified += 1

            print(
                f"✓ OGG #{item['number']:04d} "
                f"changed"
            )

        else:

            print(
                f"❌ OGG #{item['number']:04d} "
                f"verification failed"
            )

    # =====================================================
    # FINISH
    # =====================================================

    print()
    print(
        "=========================================="
    )

    print(
        "MULTIPLE OGG REPLACEMENT COMPLETE"
    )

    print(
        "=========================================="
    )

    print(
        f"Selected  : "
        f"{len(replacements)}"
    )

    print(
        f"Completed : "
        f"{completed}"
    )

    print(
        f"Verified  : "
        f"{verified}"
    )

    print()
    print(
        "Output:"
    )

    print(
        output
    )


# =========================================================
# ANALYZE
# =========================================================

def analyze(
    path,
    streams
):

    print()

    show_streams(
        path,
        streams
    )

    audio_count = 0
    unknown_count = 0

    for item in streams:

        head = read_stream_head(
            path,
            item
        )

        codec, channels, rate = (
            audio_info(head)
        )

        if codec == "UNKNOWN":
            unknown_count += 1
        else:
            audio_count += 1

    print()

    print(
        "SUMMARY"
    )

    print(
        "-" * 50
    )

    print(
        f"Total streams : "
        f"{len(streams):,}"
    )

    print(
        f"Known audio   : "
        f"{audio_count:,}"
    )

    print(
        f"Unknown       : "
        f"{unknown_count:,}"
    )

    if streams:

        print(
            f"Largest       : "
            f"{fmt_size(streams[0]['size'])}"
        )

        print(
            f"Smallest      : "
            f"{fmt_size(streams[-1]['size'])}"
        )

    print(
        "-" * 50
    )


# =========================================================
# SCAN ONLY
# =========================================================

def scan_only(
    path,
    streams
):

    print()

    print(
        "=" * 70
    )

    print(
        "SCAN RESULT"
    )

    print(
        "=" * 70
    )

    print(
        f"Valid OGG streams: "
        f"{len(streams):,}"
    )

    print()

    show_streams(
        path,
        streams,
        limit=20
    )


# =========================================================
# SCAN ENGINE
# =========================================================

def prepare_scan(path):

    print()
    print(
        "Scanning OggS pages..."
    )

    ok = scan_pages(
        path
    )

    if not ok:
        return []

    print()
    print(
        f"Valid OggS pages: "
        f"{len(PAGES):,}"
    )

    print()
    print(
        "Detecting individual streams..."
    )

    build_streams()

    print(
        f"Candidates: "
        f"{len(CANDIDATES):,}"
    )

    print()
    print(
        "Validating stream boundaries..."
    )

    streams = get_valid_streams()

    print(
        f"Valid streams: "
        f"{len(streams):,}"
    )

    return streams


# =========================================================
# MAIN MENU
# =========================================================

def main():

    print()
    print("=" * 70)

    print(
        "          NBA 2K20 OGG RESOURCE TOOL V11"
    )

    print(
        "       EXTRACT + MULTI REPLACE + ANALYZE"
    )

    print("=" * 70)

    print()

    print(
        "[1] EXTRACT OGG"
    )

    print(
        "[2] REPLACE OGG"
    )

    print(
        "[3] ANALYZE OGG"
    )

    print(
        "[4] SCAN ONLY"
    )

    print(
        "[5] EXIT"
    )

    print()

    choice = input(
        "Choose:\n> "
    ).strip()

    if choice == "5":

        print(
            "\nExiting..."
        )

        return

    if choice not in (
        "1",
        "2",
        "3",
        "4"
    ):

        print(
            "\nInvalid choice."
        )

        input(
            "\nPress ENTER..."
        )

        return

    print()

    path = normalize_path(
        input(
            "Enter OBB/IFF path:\n> "
        )
    )

    if not os.path.isfile(
        path
    ):

        print()
        print(
            "FILE NOT FOUND:"
        )

        print(path)

        input(
            "\nPress ENTER..."
        )

        return

    print()

    print(
        f"File size: "
        f"{fmt_size(os.path.getsize(path))}"
    )

    streams = prepare_scan(
        path
    )

    if not streams:

        print()
        print(
            "NO VALID INDIVIDUAL OGG STREAMS FOUND."
        )

        input(
            "\nPress ENTER..."
        )

        return

    # =====================================================
    # EXTRACT
    # =====================================================

    if choice == "1":

        folder, saved = extract_all(
            path,
            streams
        )

        print()
        print("=" * 70)

        print(
            f"SAVED: "
            f"{saved:,}"
        )

        print(
            "ORDER: LARGEST -> SMALLEST"
        )

        print()

        print(
            "OUTPUT:"
        )

        print(folder)

        print("=" * 70)

    # =====================================================
    # REPLACE
    # =====================================================

    elif choice == "2":

        replace_ogg(
            path,
            streams
        )

    # =====================================================
    # ANALYZE
    # =====================================================

    elif choice == "3":

        analyze(
            path,
            streams
        )

    # =====================================================
    # SCAN
    # =====================================================

    elif choice == "4":

        scan_only(
            path,
            streams
        )

    print()

    input(
        "Press ENTER to exit..."
    )


# =========================================================
# RUN
# =========================================================

if __name__ == "__main__":
    main()