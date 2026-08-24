# OGG Resource Tool (Android)

This is a local, offline-first tool for Android that scans, inspects, extracts, and replaces embedded OGG audio streams inside OBB and IFF resource files. 

## Features
- Scan large resource files (like .obb and .iff) for embedded `OggS` pages.
- Read stream sizes, sample rates, channels, and codec types (Vorbis/Opus).
- Audition streams securely using the internal media player via temporary caching.
- Export selected OGG streams directly to your device storage.
- Safely replace OGG streams with new audio by patching the resource file, ensuring that byte limits (slot sizes) are strictly respected.

## Technical Details
This application was ported from a web-based React tool to a native Android application using Kotlin and Jetpack Compose.
- **Framework**: Android SDK 35, Jetpack Compose, Kotlin 2.0
- **File Access**: Uses the Storage Access Framework (SAF) for broad compatibility.
- **Resource Processing**: Implements zero-copy byte extraction and limits in-memory processing to stay safe within Android RAM thresholds.
