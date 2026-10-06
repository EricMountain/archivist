# Icon generation

## Vanishing A

Generate from project root:

```shell
tools/gen-vanishing-a.py assets/vanishing-A-icon/vanishing-a.svg --copies 15 --fade 0.8 --colors rainbow --ratio 0.9

tools/gen-vanishing-a.py assets/vanishing-A-icon/vanishing-a.svg \
  --font-file assets/vanishing-A-icon/fonts/ManufacturingConsent-Regular.ttf --font ManufacturingConsent \
  --copies 12 --fade 0.8 --colors rainbow --ratio 0.8
```

`--font` only names the `font-family`; `--font-file` supplies the actual glyphs. Pass both,
or the SVG embeds one font under the other's name.

## Extruded A

This is the current icon:

```shell
tools/gen-extruded-a.py assets/vanishing-A-icon/extruded-A.svg --font-file assets/vanishing-A-icon/fonts/ManufacturingConsent-Regular.ttf --font ManufacturingConsent --depth 0 --near '#ffffff' --far '#202020'

tools/svg-to-android-icon.py assets/vanishing-A-icon/extruded-A.svg --center --dx 3 --dy 2
```

## Android launcher icon

Vector drawables can't hold text, so after regenerating the SVG, convert it to outline
paths (from the font embedded in the SVG) and rebuild the app:

```shell
# direnv (.envrc) creates the venv and installs fonttools automatically; otherwise:
pip install -r tools/requirements.txt

tools/svg-to-android-icon.py
```

This overwrites `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` in
`android/app/src/main/res/drawable/`.
