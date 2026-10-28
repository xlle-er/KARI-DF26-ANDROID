#!/usr/bin/env python3
"""Mechanical density conversion of the approved KARI artwork and padded foreground."""
from pathlib import Path
from PIL import Image
root=Path(__file__).resolve().parents[1]
image=Image.open(root/'branding/icon-rounded.png').convert('RGBA')
foreground=Image.open(root/'branding/launcher-foreground.png').convert('RGBA')
res=root/'app/src/main/res'
for density,factor in [('mdpi',1),('hdpi',1.5),('xhdpi',2),('xxhdpi',3),('xxxhdpi',4)]:
    target=res/('mipmap-'+density);target.mkdir(exist_ok=True)
    image.resize((int(48*factor),int(48*factor)),Image.Resampling.LANCZOS).save(target/'ic_launcher.png')
    foreground.resize((int(108*factor),int(108*factor)),Image.Resampling.LANCZOS).save(target/'ic_launcher_foreground.png')
adaptive=res/'mipmap-anydpi-v26';adaptive.mkdir(exist_ok=True)
(adaptive/'ic_launcher.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_launcher_background"/><foreground android:drawable="@mipmap/ic_launcher_foreground"/></adaptive-icon>\n')
themed=res/'mipmap-anydpi-v33';themed.mkdir(exist_ok=True)
(themed/'ic_launcher.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_launcher_background"/><foreground android:drawable="@mipmap/ic_launcher_foreground"/><monochrome android:drawable="@drawable/ic_launcher_monochrome"/></adaptive-icon>\n')
print('Generated legacy and adaptive launchers from icon.png')
