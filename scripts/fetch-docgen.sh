#!/usr/bin/env bash
# Stage the JavaScript libraries the document tools use into app/src/main/assets/docgen/lib/.
# They run inside the app's offline document WebView (no network, no JavaScript interface), so the agent can
# make PDF, Word, Excel and PowerPoint files without Python or npm on the phone. Pinned versions; `npm pack`
# checks each tarball against the registry's integrity hash. The output is gitignored.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/app/src/main/assets/docgen/lib"
packages=(pdfmake@0.3.13 html-to-pdfmake@2.5.36 docx@9.14.0 xlsx@0.18.5 pptxgenjs@4.0.1
  @fontsource/noto-sans-tamil@5.3.0 @fontsource/noto-sans-devanagari@5.3.0)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for p in "${packages[@]}"; do
  name="${p%@*}"
  name="${name#@fontsource/}"
  tgz="$(cd "$work" && npm pack --silent "$p" | tail -1)"
  mkdir -p "$work/$name"
  tar -xzf "$work/$tgz" -C "$work/$name" --strip-components=1
done

rm -rf "$out"
mkdir -p "$out/licenses"
cp "$work/pdfmake/build/pdfmake.min.js" "$out/pdfmake.min.js"
cp "$work/pdfmake/build/vfs_fonts.js" "$out/vfs_fonts.js"
cp "$work/html-to-pdfmake/browser.js" "$out/html-to-pdfmake.js"
cp "$work/docx/dist/index.iife.js" "$out/docx.js"
cp "$work/docx/dist/math.iife.js" "$out/docx-math.js"
cp "$work/docx/dist/html.iife.js" "$out/docx-html.js"
cp "$work/xlsx/dist/xlsx.full.min.js" "$out/xlsx.full.min.js"
cp "$work/pptxgenjs/dist/pptxgen.bundle.js" "$out/pptxgen.bundle.js"
# Tamil and Devanagari fonts for PDFs (pdfmake's Roboto has neither), as a script the engine can load offline.
{
  echo "pdfMake.addVirtualFileSystem({"
  for script in tamil devanagari; do
    for weight in 400 700; do
      printf '"noto-%s-%s.woff":"%s",\n' "$script" "$weight" \
        "$(base64 -w0 "$work/noto-sans-$script/files/noto-sans-$script-$script-$weight-normal.woff")"
    done
  done
  echo "});"
} > "$out/fonts-indic.js"
for name in pdfmake html-to-pdfmake docx xlsx pptxgenjs noto-sans-tamil noto-sans-devanagari; do
  lic="$(ls "$work/$name" | grep -i -m1 '^licen[cs]e' || true)"
  [ -n "$lic" ] && cp "$work/$name/$lic" "$out/licenses/$name-LICENSE.txt"
done
printf '%s\n' "${packages[@]}" > "$out/VERSIONS.txt"
echo "Staged document libraries in $out"
du -sh "$out"
