#!/usr/bin/env bash
set -euo pipefail

readonly FONT_REVISION="ddf386ba1b9fbc6b2c818f1ddcd9148f54ced94b"
readonly FONT_URL="https://raw.githubusercontent.com/google/fonts/$FONT_REVISION/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"
readonly FONT_SOURCE_SHA256="a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da"
readonly FONTTOOLS_VERSION="4.60.1"
readonly FONTTOOLS_URL="https://files.pythonhosted.org/packages/c0/ca/4bb48a26ed95a1e7eba175535fe5805887682140ee0a0d10a88e1de84208/fonttools-4.60.1-cp312-cp312-manylinux1_x86_64.manylinux2014_x86_64.manylinux_2_17_x86_64.manylinux_2_5_x86_64.whl"
readonly FONTTOOLS_SHA256="8177ec9676ea6e1793c8a084a90b65a9f778771998eb919d05db6d4b1c0b114c"
readonly SUBSET_SHA256="625dbaad3dd34cfa467ca755115be981b5b44aef9af33d5b09c5f2bdc8040b8f"
readonly CHARACTERS="shared/ui/src/commonMain/composeResources/files/NotoSansSC-WallHub-characters.txt"
readonly COMMITTED_SUBSET="shared/ui/src/commonMain/composeResources/font/noto_sans_sc_wallhub.ttf"

[[ "$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')" == "3.12" ]] || {
    printf 'ERROR: Python 3.12 is required for the pinned FontTools wheel.\n' >&2
    exit 1
}

temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
source_font="$temporary/NotoSansSC-variable.ttf"
wheel="$temporary/fonttools-4.60.1-cp312-cp312-manylinux1_x86_64.manylinux2014_x86_64.manylinux_2_17_x86_64.manylinux_2_5_x86_64.whl"
generated="$temporary/noto_sans_sc_wallhub.ttf"

curl --location --fail --show-error --silent --retry 5 --retry-all-errors \
    --output "$source_font" "$FONT_URL"
echo "$FONT_SOURCE_SHA256  $source_font" | sha256sum --check --status || {
    printf 'ERROR: Upstream Noto Sans SC source hash mismatch.\n' >&2
    exit 1
}

curl --location --fail --show-error --silent --retry 5 --retry-all-errors \
    --output "$wheel" "$FONTTOOLS_URL"
echo "$FONTTOOLS_SHA256  $wheel" | sha256sum --check --status || {
    printf 'ERROR: FontTools wheel hash mismatch.\n' >&2
    exit 1
}
python3 -m pip install --quiet --disable-pip-version-check --root-user-action=ignore --no-index --no-deps \
    --target "$temporary/fonttools" "$wheel"

PYTHONPATH="$temporary/fonttools" python3 -m fontTools.subset \
    "$source_font" \
    --text-file="$CHARACTERS" \
    --layout-features='*' \
    --glyph-names \
    --symbol-cmap \
    --legacy-cmap \
    --notdef-glyph \
    --notdef-outline \
    --recommended-glyphs \
    --name-IDs='*' \
    --name-legacy \
    --name-languages='*' \
    --output-file="$generated"

echo "$SUBSET_SHA256  $generated" | sha256sum --check --status || {
    printf 'ERROR: Regenerated font subset is not byte-for-byte reproducible.\n' >&2
    exit 1
}
cmp --silent "$generated" "$COMMITTED_SUBSET" || {
    printf 'ERROR: Committed font subset differs from regenerated output.\n' >&2
    exit 1
}
printf 'WEB_FONT_SUBSET=OK fonttools=%s source=%s subset=%s\n' \
    "$FONTTOOLS_VERSION" "$FONT_SOURCE_SHA256" "$SUBSET_SHA256"
