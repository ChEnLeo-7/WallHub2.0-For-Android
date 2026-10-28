#!/usr/bin/env python3
"""Print a deterministic hash of every primary Web preview build input."""

from hashlib import sha256
from pathlib import Path

FILES = (
    Path("scripts/build-web-preview.sh"),
    Path("scripts/sync-web-ui-contract.mjs"),
    Path("scripts/web-preview-source-hash.py"),
    Path("preview/web-dom/build.mjs"),
    Path("preview/web-dom/index.html"),
    Path("preview/web-dom/package.json"),
    Path("preview/web-dom/package-lock.json"),
    Path("preview/web-dom/src/contract.css"),
    Path("preview/web-dom/src/main.js"),
    Path("preview/web-dom/src/styles.css"),
    Path("shared/ui-contract/wallhub-current.json"),
    Path("shared/ui/src/commonMain/composeResources/font/noto_sans_sc_wallhub.ttf"),
    Path("shared/ui/src/commonMain/kotlin/com/wallhub/shared/ui/WallHubDesignTokens.kt"),
    Path("shared/ui/src/commonMain/kotlin/com/wallhub/shared/ui/WallHubWindowSizeClass.kt"),
    Path("app/src/main/kotlin/com/wallhub/android/core/designsystem/WallHubDesignTokens.kt"),
    Path("app/src/main/kotlin/com/wallhub/android/core/designsystem/WallHubTheme.kt"),
    Path("app/src/main/kotlin/com/wallhub/android/navigation/TopLevelDestinations.kt"),
    Path("app/src/main/res/values-zh-rCN/strings.xml"),
)

digest = sha256()
for path in FILES:
    resolved = path.resolve(strict=True)
    digest.update(path.as_posix().encode())
    digest.update(b"\0")
    digest.update(resolved.read_bytes())
    digest.update(b"\0")
print(digest.hexdigest())
