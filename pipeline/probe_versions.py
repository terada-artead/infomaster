"""Android 側で使う依存の実在バージョンを Maven のメタデータから確認する。"""

import re

import httpx

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"

TARGETS = [
    ("AGP", f"{GOOGLE}/com/android/tools/build/gradle/maven-metadata.xml"),
    ("Kotlin", f"{CENTRAL}/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml"),
    ("compose-bom", f"{GOOGLE}/androidx/compose/compose-bom/maven-metadata.xml"),
    ("activity-compose", f"{GOOGLE}/androidx/activity/activity-compose/maven-metadata.xml"),
    ("lifecycle-vm-compose", f"{GOOGLE}/androidx/lifecycle/lifecycle-viewmodel-compose/maven-metadata.xml"),
    ("work-runtime-ktx", f"{GOOGLE}/androidx/work/work-runtime-ktx/maven-metadata.xml"),
    ("browser", f"{GOOGLE}/androidx/browser/browser/maven-metadata.xml"),
    ("core-ktx", f"{GOOGLE}/androidx/core/core-ktx/maven-metadata.xml"),
    ("okhttp", f"{CENTRAL}/com/squareup/okhttp3/okhttp/maven-metadata.xml"),
    ("kotlinx-serialization-json", f"{CENTRAL}/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml"),
    ("kotlinx-datetime", f"{CENTRAL}/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml"),
]


def stable(versions: list[str]) -> list[str]:
    """alpha/beta/rc/dev を除いた安定版だけ返す。"""
    bad = re.compile(r"alpha|beta|-rc|dev|-M\d|Beta|RC", re.IGNORECASE)
    return [v for v in versions if not bad.search(v)]


for name, url in TARGETS:
    try:
        resp = httpx.get(url, timeout=25)
        resp.raise_for_status()
    except Exception as exc:
        print(f"{name:28} ERROR {exc}")
        continue
    versions = re.findall(r"<version>([^<]+)</version>", resp.text)
    latest_stable = stable(versions)[-5:]
    print(f"{name:28} latest stable: {', '.join(latest_stable)}")
